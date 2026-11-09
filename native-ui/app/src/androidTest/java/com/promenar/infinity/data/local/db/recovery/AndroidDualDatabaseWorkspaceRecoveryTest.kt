package com.promenar.infinity.data.local.db.recovery

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.backup.AndroidRestoreJournalAuthenticator
import com.promenar.infinity.data.backup.BackupContent
import com.promenar.infinity.data.backup.BackupPreferenceSnapshot
import com.promenar.infinity.data.backup.RoomBackupDataSource
import com.promenar.infinity.data.backup.TransactionalBackupPreferenceStore
import com.promenar.infinity.data.backup.TransactionalBackupSecretStore
import com.promenar.infinity.data.local.db.INFINITY_DATABASE_MIGRATIONS
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.repository.WorkspaceRepository
import com.promenar.infinity.data.security.SecretId
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class AndroidDualDatabaseWorkspaceRecoveryTest {
    @Test
    fun systemDataAliasIsNormalizedOnlyInsideWorkingCopies() {
        runBlocking<Unit> {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val target = instrumentation.targetContext
            val runId = UUID.randomUUID().toString()
            val fixture = Files.createDirectory(target.dataDir.toPath().resolve("dual-db-alias-$runId"))
            val context = AnchoredIsolatedContext(target, fixture.toFile())
            val key = "infinity.dual-db.alias.$runId"
            var room: InfinityDatabase? = null
            try {
                val ragRoot = context.filesDir.resolve("rag_workspace").apply { check(mkdir()) }
                ragRoot.resolve("knowledge.txt").writeText("系统别名知识库")
                val aliasDataRoot = Paths.get("/data/data", target.packageName)
                assertThat(Files.isSameFile(aliasDataRoot, target.dataDir.toPath())).isTrue()
                val aliasRagRoot = aliasDataRoot.resolve(target.dataDir.toPath().relativize(ragRoot.toPath()))
                assertThat(Files.isSameFile(aliasRagRoot, ragRoot.toPath())).isTrue()
                val legacyDb = context.getDatabasePath(LEGACY_DATABASE_NAME)
                val currentDb = context.getDatabasePath(CURRENT_DATABASE_NAME)
                createPublishedFixture(17, legacyDb)
                createPublishedFixture(2, currentDb)
                seedRagWorkspace(legacyDb, "legacy", aliasRagRoot.toFile(), withSession = false)
                val legacySourceHash = digest(legacyDb)
                val journal = FileDualDatabaseRecoveryJournal(
                    context.noBackupFilesDir.toPath(), AndroidRestoreJournalAuthenticator(key),
                )
                val snapshots = RecoverySnapshotStore(
                    setOf(legacyDb.parentFile!!.toPath(), context.filesDir.toPath()),
                    10_000,
                    128L * 1024 * 1024,
                    context.dataDir.toPath(),
                )
                val bootstrap = DualDatabaseRecoveryBootstrap(
                    context.noBackupFilesDir.toPath(),
                    AndroidDualDatabaseSourceProbe(context),
                    journal,
                    AndroidDualDatabaseRecoveryExecutor(context, journal, snapshots),
                )
                val offer = bootstrap.inspectOrResume() as DualDatabaseBootstrapResult.RecoveryRequired
                assertThat(bootstrap.recover(offer.offer.offerToken))
                    .isInstanceOf(DualDatabaseBootstrapResult.Completed::class.java)
                val record = requireNotNull(journal.read())
                val archivedLegacy = context.noBackupFilesDir.toPath()
                    .resolve("dual-db-source-archives-v1")
                    .resolve(record.transactionId)
                    .resolve("files")
                    .resolve(LEGACY_DATABASE_NAME)
                    .toFile()
                assertThat(digest(archivedLegacy)).isEqualTo(legacySourceHash)

                val database = Room.databaseBuilder(context, InfinityDatabase::class.java, CURRENT_DATABASE_NAME)
                    // 恢复候选冻结为 v18，打开当前库需经应用注册的全部前进迁移。
                    .addMigrations(*INFINITY_DATABASE_MIGRATIONS)
                    .allowMainThreadQueries()
                    .build()
                room = database
                val repository = WorkspaceRepository(
                    database.fileEntryDao(), database.workspaceSeqDao(), context.filesDir.resolve("session_workspaces"),
                )
                val recovered = repository.ensureSessionRoot(RAG_WORKSPACE_SESSION_ID)
                assertThat(File(recovered.physicalRootPath, "knowledge.txt").readText())
                    .isEqualTo("系统别名知识库")
                assertThat(aliasRagRoot.resolve("knowledge.txt").toFile().readText())
                    .isEqualTo("系统别名知识库")
            } finally {
                room?.close()
                KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(key) }
                fixture.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun realDataPathIsNormalizedWhenContextSuppliesTheSystemAlias() {
        runBlocking<Unit> {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val target = instrumentation.targetContext
            val runId = UUID.randomUUID().toString()
            val fixture = Files.createDirectory(target.dataDir.toPath().resolve("dual-db-real-alias-$runId"))
            val context = SystemAliasIsolatedContext(target, fixture.toFile())
            val key = "infinity.dual-db.real.alias.$runId"
            var room: InfinityDatabase? = null
            try {
                val declaredRagRoot = context.filesDir.resolve("rag_workspace").apply { check(mkdir()) }
                declaredRagRoot.resolve("knowledge.txt").writeText("反向系统别名知识库")
                val realRagRoot = fixture.resolve("files/rag_workspace")
                assertThat(Files.isSameFile(declaredRagRoot.toPath(), realRagRoot)).isTrue()
                val legacyDb = context.getDatabasePath(LEGACY_DATABASE_NAME)
                val currentDb = context.getDatabasePath(CURRENT_DATABASE_NAME)
                createPublishedFixture(17, legacyDb)
                createPublishedFixture(2, currentDb)
                seedRagWorkspace(legacyDb, "legacy", realRagRoot.toFile(), withSession = false)
                val journal = FileDualDatabaseRecoveryJournal(
                    context.noBackupFilesDir.toPath(), AndroidRestoreJournalAuthenticator(key),
                )
                val snapshots = RecoverySnapshotStore(
                    setOf(legacyDb.parentFile!!.toPath(), context.filesDir.toPath()),
                    10_000,
                    128L * 1024 * 1024,
                    context.dataDir.toPath(),
                )
                val bootstrap = DualDatabaseRecoveryBootstrap(
                    context.noBackupFilesDir.toPath(),
                    AndroidDualDatabaseSourceProbe(context),
                    journal,
                    AndroidDualDatabaseRecoveryExecutor(context, journal, snapshots),
                )
                val offer = bootstrap.inspectOrResume() as DualDatabaseBootstrapResult.RecoveryRequired
                assertThat(bootstrap.recover(offer.offer.offerToken))
                    .isInstanceOf(DualDatabaseBootstrapResult.Completed::class.java)

                val database = Room.databaseBuilder(context, InfinityDatabase::class.java, CURRENT_DATABASE_NAME)
                    // 恢复候选冻结为 v18，打开当前库需经应用注册的全部前进迁移。
                    .addMigrations(*INFINITY_DATABASE_MIGRATIONS)
                    .allowMainThreadQueries()
                    .build()
                room = database
                val repository = WorkspaceRepository(
                    database.fileEntryDao(), database.workspaceSeqDao(), context.filesDir.resolve("session_workspaces"),
                )
                val recovered = repository.ensureSessionRoot(RAG_WORKSPACE_SESSION_ID)
                assertThat(File(recovered.physicalRootPath, "knowledge.txt").readText())
                    .isEqualTo("反向系统别名知识库")
                assertThat(realRagRoot.resolve("knowledge.txt").toFile().readText())
                    .isEqualTo("反向系统别名知识库")
            } finally {
                room?.close()
                KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(key) }
                fixture.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun systemDataAliasDoesNotPermitSymlinkInsideRecoveredTree() {
        runBlocking<Unit> {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val target = instrumentation.targetContext
            val runId = UUID.randomUUID().toString()
            val fixture = Files.createDirectory(target.dataDir.toPath().resolve("dual-db-alias-link-$runId"))
            val context = AnchoredIsolatedContext(target, fixture.toFile())
            val key = "infinity.dual-db.alias.link.$runId"
            try {
                val ragRoot = context.filesDir.resolve("rag_workspace").apply { check(mkdir()) }
                ragRoot.resolve("knowledge.txt").writeText("安全内容")
                val outside = fixture.resolve("outside.txt").also { it.toFile().writeText("不可跟随") }
                Files.createSymbolicLink(ragRoot.toPath().resolve("escape.txt"), outside)
                val aliasDataRoot = Paths.get("/data/data", target.packageName)
                val aliasRagRoot = aliasDataRoot.resolve(target.dataDir.toPath().relativize(ragRoot.toPath()))
                val legacyDb = context.getDatabasePath(LEGACY_DATABASE_NAME)
                val currentDb = context.getDatabasePath(CURRENT_DATABASE_NAME)
                createPublishedFixture(17, legacyDb)
                createPublishedFixture(2, currentDb)
                seedRagWorkspace(legacyDb, "legacy", aliasRagRoot.toFile(), withSession = false)
                val journal = FileDualDatabaseRecoveryJournal(
                    context.noBackupFilesDir.toPath(), AndroidRestoreJournalAuthenticator(key),
                )
                val snapshots = RecoverySnapshotStore(
                    setOf(legacyDb.parentFile!!.toPath(), context.filesDir.toPath()),
                    10_000,
                    128L * 1024 * 1024,
                    context.dataDir.toPath(),
                )
                val bootstrap = DualDatabaseRecoveryBootstrap(
                    context.noBackupFilesDir.toPath(),
                    AndroidDualDatabaseSourceProbe(context),
                    journal,
                    AndroidDualDatabaseRecoveryExecutor(context, journal, snapshots),
                )
                val offer = bootstrap.inspectOrResume() as DualDatabaseBootstrapResult.RecoveryRequired

                val result = bootstrap.recover(offer.offer.offerToken)

                assertThat(result).isInstanceOf(DualDatabaseBootstrapResult.Blocked::class.java)
                assertThat((result as DualDatabaseBootstrapResult.Blocked).detail).contains("符号链接")
                assertThat(outside.toFile().readText()).isEqualTo("不可跟随")
            } finally {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(key) }
                fixture.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun legacyOnlyRagRootBecomesTheDefaultReachableSystemSession() {
        runBlocking<Unit> {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val runId = UUID.randomUUID().toString()
            val root = Files.createDirectory(instrumentation.targetContext.filesDir.toPath().resolve("dual-db-rag-only-$runId"))
            val context = IsolatedContext(instrumentation.targetContext, root.toFile())
            val key = "infinity.dual-db.rag.only.$runId"
            var room: InfinityDatabase? = null
            try {
                val ragRoot = context.filesDir.resolve("rag_workspace").apply { check(mkdir()) }
                ragRoot.resolve("knowledge.txt").writeText("仅旧版知识库内容")
                val legacyDb = context.getDatabasePath(LEGACY_DATABASE_NAME)
                val currentDb = context.getDatabasePath(CURRENT_DATABASE_NAME)
                createPublishedFixture(17, legacyDb)
                createPublishedFixture(2, currentDb)
                seedRagWorkspace(legacyDb, "legacy", ragRoot, withSession = false)
                val journal = FileDualDatabaseRecoveryJournal(
                    context.noBackupFilesDir.toPath(),
                    AndroidRestoreJournalAuthenticator(key),
                )
                val snapshots = RecoverySnapshotStore(
                    setOf(legacyDb.parentFile!!.toPath(), context.filesDir.toPath()),
                    10_000,
                    64L * 1024 * 1024,
                    context.dataDir.toPath(),
                )
                val bootstrap = DualDatabaseRecoveryBootstrap(
                    context.noBackupFilesDir.toPath(),
                    AndroidDualDatabaseSourceProbe(context),
                    journal,
                    AndroidDualDatabaseRecoveryExecutor(context, journal, snapshots),
                )
                val offer = bootstrap.inspectOrResume() as DualDatabaseBootstrapResult.RecoveryRequired
                assertThat(bootstrap.recover(offer.offer.offerToken))
                    .isInstanceOf(DualDatabaseBootstrapResult.Completed::class.java)

                val database = Room.databaseBuilder(context, InfinityDatabase::class.java, CURRENT_DATABASE_NAME)
                    // 恢复候选冻结为 v18，打开当前库需经应用注册的全部前进迁移。
                    .addMigrations(*INFINITY_DATABASE_MIGRATIONS)
                    .allowMainThreadQueries()
                    .build()
                room = database
                val repository = WorkspaceRepository(
                    database.fileEntryDao(), database.workspaceSeqDao(), context.filesDir.resolve("session_workspaces"),
                )
                val recoveredRoot = repository.ensureSessionRoot(RAG_WORKSPACE_SESSION_ID)

                assertThat(File(recoveredRoot.physicalRootPath, "knowledge.txt").readText())
                    .isEqualTo("仅旧版知识库内容")
                database.openHelper.readableDatabase.query(
                    "SELECT agent_id,workspace_root_uuid FROM sessions WHERE id=?",
                    arrayOf(RAG_WORKSPACE_SESSION_ID),
                ).use { cursor ->
                    assertThat(cursor.moveToFirst()).isTrue()
                    assertThat(cursor.getString(0)).isEqualTo(RAG_SYSTEM_AGENT_ID)
                    assertThat(cursor.getString(1)).isEqualTo(recoveredRoot.uuid)
                }
            } finally {
                room?.close()
                KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(key) }
                root.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun legacyRagRootWithoutSessionAndCurrentRagRemainSeparateAndReachable() {
        runBlocking<Unit> {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val runId = UUID.randomUUID().toString()
            val root = Files.createDirectory(instrumentation.targetContext.filesDir.toPath().resolve("dual-db-rag-$runId"))
            val context = IsolatedContext(instrumentation.targetContext, root.toFile())
            val key = "infinity.dual-db.rag.$runId"
            var room: InfinityDatabase? = null
            try {
                val ragRoot = context.filesDir.resolve("rag_workspace").apply { check(mkdir()) }
                ragRoot.resolve("knowledge.txt").writeText("历史知识库内容")
                val legacyDb = context.getDatabasePath(LEGACY_DATABASE_NAME)
                val currentDb = context.getDatabasePath(CURRENT_DATABASE_NAME)
                createPublishedFixture(17, legacyDb)
                createPublishedFixture(2, currentDb)
                seedRagWorkspace(legacyDb, "legacy", ragRoot, withSession = false)
                seedRagWorkspace(currentDb, "current", ragRoot, withSession = true)
                val journal = FileDualDatabaseRecoveryJournal(
                    context.noBackupFilesDir.toPath(),
                    AndroidRestoreJournalAuthenticator(key),
                )
                val snapshots = RecoverySnapshotStore(
                    setOf(legacyDb.parentFile!!.toPath(), context.filesDir.toPath()),
                    10_000,
                    64L * 1024 * 1024,
                    context.dataDir.toPath(),
                )
                val bootstrap = DualDatabaseRecoveryBootstrap(
                    context.noBackupFilesDir.toPath(),
                    AndroidDualDatabaseSourceProbe(context),
                    journal,
                    AndroidDualDatabaseRecoveryExecutor(context, journal, snapshots),
                )
                val offer = bootstrap.inspectOrResume() as DualDatabaseBootstrapResult.RecoveryRequired
                assertThat(bootstrap.recover(offer.offer.offerToken))
                    .isInstanceOf(DualDatabaseBootstrapResult.Completed::class.java)

                val database = Room.databaseBuilder(context, InfinityDatabase::class.java, CURRENT_DATABASE_NAME)
                    // 恢复候选冻结为 v18，打开当前库需经应用注册的全部前进迁移。
                    .addMigrations(*INFINITY_DATABASE_MIGRATIONS)
                    .allowMainThreadQueries()
                    .build()
                room = database
                val repository = WorkspaceRepository(
                    database.fileEntryDao(), database.workspaceSeqDao(), context.filesDir.resolve("session_workspaces"),
                )
                val legacySession = RecoveryStableId.map("sessions", "id", RAG_WORKSPACE_SESSION_ID)
                val currentRoot = repository.ensureSessionRoot(RAG_WORKSPACE_SESSION_ID)
                val legacyRoot = repository.ensureSessionRoot(legacySession)

                assertThat(currentRoot.physicalRootPath).isNotEqualTo(legacyRoot.physicalRootPath)
                assertThat(File(currentRoot.physicalRootPath, "knowledge.txt").readText()).isEqualTo("历史知识库内容")
                assertThat(File(legacyRoot.physicalRootPath, "knowledge.txt").readText()).isEqualTo("历史知识库内容")
                database.openHelper.readableDatabase.query(
                    "SELECT id FROM sessions WHERE agent_id='__system__' AND workspace_root_uuid IS NOT NULL ORDER BY id",
                ).use { cursor ->
                    val ids = buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                    assertThat(ids).containsExactly(RAG_WORKSPACE_SESSION_ID, legacySession)
                }
            } finally {
                room?.close()
                KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(key) }
                root.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun workspaceFilesRemainReadableAndExportableAfterRecovery() {
        runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val runId = UUID.randomUUID().toString()
        val root = Files.createDirectory(instrumentation.targetContext.filesDir.toPath().resolve("dual-db-workspace-$runId"))
        val context = IsolatedContext(instrumentation.targetContext, root.toFile())
        val recoveryKey = "infinity.dual-db.workspace.$runId"
        val backupKey = "infinity.dual-db.workspace.backup.$runId"
        var room: InfinityDatabase? = null
        try {
            val legacySource = context.filesDir.resolve("legacy-source").apply { check(mkdir()) }
            val currentSource = context.filesDir.resolve("current-source").apply { check(mkdir()) }
            legacySource.resolve("legacy.txt").writeText("旧工作区内容")
            currentSource.resolve("current.txt").writeText("当前工作区内容")
            context.filesDir.resolve("legacy-avatar.bin").writeText("旧头像内容")
            context.filesDir.resolve("current-avatar.bin").writeText("当前头像内容")
            context.filesDir.resolve("legacy-attachment.bin").writeText("旧附件内容")
            context.filesDir.resolve("current-attachment.bin").writeText("当前附件内容")
            val legacyDb = context.getDatabasePath(LEGACY_DATABASE_NAME)
            val currentDb = context.getDatabasePath(CURRENT_DATABASE_NAME)
            createPublishedFixture(17, legacyDb)
            createPublishedFixture(2, currentDb)
            seedWorkspace(legacyDb, "legacy", legacySource, includeVersion = false)
            seedWorkspace(currentDb, "current", currentSource, includeVersion = true)
            val legacySourceHash = digest(legacySource.resolve("legacy.txt"))
            val currentSourceHash = digest(currentSource.resolve("current.txt"))

            val journal = FileDualDatabaseRecoveryJournal(
                context.noBackupFilesDir.toPath(),
                AndroidRestoreJournalAuthenticator(recoveryKey),
            )
            val snapshots = RecoverySnapshotStore(
                setOf(legacyDb.parentFile!!.toPath(), context.filesDir.toPath()),
                10_000,
                64L * 1024 * 1024,
                context.dataDir.toPath(),
            )
            val bootstrap = DualDatabaseRecoveryBootstrap(
                context.noBackupFilesDir.toPath(),
                AndroidDualDatabaseSourceProbe(context),
                journal,
                AndroidDualDatabaseRecoveryExecutor(context, journal, snapshots),
            )
            val offer = bootstrap.inspectOrResume() as DualDatabaseBootstrapResult.RecoveryRequired
            assertThat(bootstrap.recover(offer.offer.offerToken))
                .isInstanceOf(DualDatabaseBootstrapResult.Completed::class.java)

            val database = Room.databaseBuilder(context, InfinityDatabase::class.java, CURRENT_DATABASE_NAME)
                // 恢复候选冻结为 v18，打开当前库需经应用注册的全部前进迁移。
                .addMigrations(*INFINITY_DATABASE_MIGRATIONS)
                .allowMainThreadQueries()
                .build()
            room = database
            val repository = WorkspaceRepository(
                database.fileEntryDao(),
                database.workspaceSeqDao(),
                context.filesDir.resolve("session_workspaces"),
            )
            val legacySession = RecoveryStableId.map("sessions", "id", "legacy-session")
            val legacyRootId = RecoveryStableId.map("workspace_files", "uuid", "legacy-root")
            val legacyFileId = RecoveryStableId.map("workspace_files", "uuid", "legacy-file")
            val currentRoot = repository.ensureSessionRoot("current-session")
            val recoveredLegacyRoot = repository.ensureSessionRoot(legacySession)
            val currentFile = checkNotNull(repository.getByUuid("current-root", "current-file"))
            val legacyFile = checkNotNull(repository.getByUuid(legacyRootId, legacyFileId))

            assertThat(Files.isSameFile(
                File(currentRoot.physicalRootPath).parentFile.toPath(),
                context.filesDir.resolve("workspaces").toPath(),
            )).isTrue()
            assertThat(Files.isSameFile(
                File(recoveredLegacyRoot.physicalRootPath).parentFile.toPath(),
                context.filesDir.resolve("workspaces").toPath(),
            )).isTrue()
            assertThat(File(currentRoot.physicalRootPath).name).startsWith("recovered-")
            assertThat(File(recoveredLegacyRoot.physicalRootPath).name).startsWith("recovered-")
            assertThat(File(currentFile.physicalRootPath, currentFile.materializedPath.removePrefix("/")).readText())
                .isEqualTo("当前工作区内容")
            assertThat(File(legacyFile.physicalRootPath, legacyFile.materializedPath.removePrefix("/")).readText())
                .isEqualTo("旧工作区内容")
            assertThat(digest(currentSource.resolve("current.txt"))).isEqualTo(currentSourceHash)
            assertThat(digest(legacySource.resolve("legacy.txt"))).isEqualTo(legacySourceHash)
            database.openHelper.readableDatabase.query(
                "SELECT avatar_path FROM agents ORDER BY id",
            ).use { cursor ->
                val paths = buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                assertThat(paths).hasSize(2)
                assertThat(paths.map { File(it).readText() }).containsExactly("当前头像内容", "旧头像内容")
                assertThat(paths.all { it.contains("/recovered-assets/") }).isTrue()
            }
            database.openHelper.readableDatabase.query(
                "SELECT legacy_attachments FROM messages WHERE id=?",
                arrayOf(RecoveryStableId.map("messages", "id", "legacy-message")),
            ).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                val item = LegacyAttachmentCodec.parse(cursor.getString(0)).single()
                assertThat(item.uri).contains("/recovered-assets/")
                val opened = LegacyAttachmentCodec.open(context, item)
                if (opened is LegacyAttachmentOpenResult.Unreadable) throw AssertionError(opened.reason)
                val uri = (opened as LegacyAttachmentOpenResult.Ready).intent.data
                val bytes = context.contentResolver.openInputStream(requireNotNull(uri))!!.use { it.readBytes() }
                assertThat(bytes.toString(Charsets.UTF_8)).isEqualTo("旧附件内容")
            }
            database.openHelper.readableDatabase.query(
                "SELECT files FROM messages WHERE id='current-message'",
            ).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                val rewritten = Json.parseToJsonElement(cursor.getString(0)).jsonArray.single().jsonPrimitive.content
                assertThat(rewritten).contains("/recovered-assets/")
                assertThat(File(requireNotNull(android.net.Uri.parse(rewritten).path)).readText())
                    .isEqualTo("当前附件内容")
            }

            val backup = RoomBackupDataSource(
                database = database,
                preferences = EmptyPreferences,
                secrets = EmptySecrets,
                trustedSourceBases = setOf(
                    context.filesDir.toPath(),
                    context.filesDir.resolve("WorkSpace").apply { mkdirs() }.toPath(),
                    context.filesDir.resolve("workspaces").toPath(),
                ),
                trustedRestoreBase = context.noBackupFilesDir.resolve("backup-test").apply { mkdirs() }.toPath(),
                appVersion = "test",
                journalAuthenticator = AndroidRestoreJournalAuthenticator(backupKey),
            ).snapshot(setOf(BackupContent.DATABASE, BackupContent.PREFERENCES, BackupContent.FILES))
            try {
                assertThat(backup.files.values.map { it.toString(Charsets.UTF_8) })
                    .containsAtLeast("当前工作区内容", "旧工作区内容")
            } finally {
                backup.database.fill(0)
                backup.preferences.fill(0)
                backup.files.values.forEach { it.fill(0) }
            }
            val declaredBase = context.filesDir.resolve("workspaces").toPath()
            val canonicalBase = declaredBase.toRealPath()
            assertThat(declaredBase).isNotEqualTo(canonicalBase)
            assertThat(Files.isSameFile(declaredBase, canonicalBase)).isTrue()
            val ambiguousFailure = runCatching {
                RoomBackupDataSource(
                    database = database,
                    preferences = EmptyPreferences,
                    secrets = EmptySecrets,
                    trustedSourceBases = linkedSetOf(declaredBase, canonicalBase),
                    trustedRestoreBase = context.noBackupFilesDir.resolve("backup-test").toPath(),
                    appVersion = "test",
                    journalAuthenticator = AndroidRestoreJournalAuthenticator(backupKey),
                ).snapshot(setOf(BackupContent.DATABASE, BackupContent.PREFERENCES, BackupContent.FILES))
            }.exceptionOrNull()
            assertThat(ambiguousFailure).isInstanceOf(com.promenar.infinity.data.backup.BackupValidationException::class.java)
            assertThat(ambiguousFailure?.message).contains("多个受信父目录")
        } finally {
            room?.close()
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(recoveryKey)
                deleteEntry(backupKey)
            }
            root.toFile().deleteRecursively()
        }
        }
    }

    private fun createPublishedFixture(version: Int, path: File) {
        val fixture = InstrumentationRegistry.getInstrumentation().context.assets.open("public-v$version.json").use {
            Json.parseToJsonElement(it.bufferedReader().readText()).jsonObject
        }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { database ->
            fixture.getValue("statements").jsonArray.forEach { database.execSQL(it.jsonPrimitive.content) }
            database.execSQL(
                "INSERT INTO room_master_table(id,identity_hash) VALUES(42,?)",
                arrayOf(fixture.getValue("roomIdentity").jsonPrimitive.content),
            )
            database.version = version
        }
    }

    private fun seedWorkspace(databaseFile: File, prefix: String, source: File, includeVersion: Boolean) {
        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            insert(database, "agents", mapOf(
                "id" to "$prefix-agent",
                "name" to prefix,
                "avatar_path" to source.parentFile!!.resolve("$prefix-avatar.bin").path,
            ))
            insert(database, "sessions", mapOf(
                "id" to "$prefix-session", "agent_id" to "$prefix-agent", "title" to prefix,
                "workspace_path" to source.path, "workspace_root_uuid" to "$prefix-root",
            ))
            insert(database, "workspace_files", mapOf(
                "uuid" to "$prefix-root", "workspace_root_uuid" to "$prefix-root", "name" to prefix,
                "hash" to "old-root-identity", "is_directory" to 1, "physical_root_path" to source.path,
                "materialized_path" to "/",
            ))
            val contentFile = source.resolve("$prefix.txt")
            insert(database, "workspace_files", mapOf(
                "uuid" to "$prefix-file", "workspace_root_uuid" to "$prefix-root", "parent_uuid" to "$prefix-root",
                "name" to contentFile.name, "hash" to digest(contentFile), "size_bytes" to contentFile.length(),
                "is_directory" to 0, "mime_type" to "text/plain", "physical_root_path" to source.path,
                "materialized_path" to "/${contentFile.name}",
            ))
            if (includeVersion) insert(database, "file_versions", mapOf(
                "id" to "$prefix-version", "file_uuid" to "$prefix-file", "workspace_root_uuid" to "$prefix-root",
                "hash" to digest(contentFile), "content_path" to contentFile.path,
                "created_by_session_id" to "$prefix-session",
            ))
            val attachment = source.parentFile!!.resolve("$prefix-attachment.bin")
            val attachmentUri = android.net.Uri.fromFile(attachment).toString()
            val legacyPayload = """[{"uri":"$attachmentUri","mimeType":"application/octet-stream","fileName":"${attachment.name}","sizeBytes":${attachment.length()},"type":"DOCUMENT"}]"""
            insert(database, "messages", mapOf(
                "id" to "$prefix-message",
                "session_id" to "$prefix-session",
                "role" to "user",
                "content" to "$prefix message",
                "attachments" to legacyPayload,
                "files" to "[\"$attachmentUri\"]",
                "created_at" to 1L,
            ))
        }
    }

    private fun seedRagWorkspace(databaseFile: File, prefix: String, source: File, withSession: Boolean) {
        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            if (withSession) insert(database, "sessions", mapOf(
                "id" to RAG_WORKSPACE_SESSION_ID,
                "agent_id" to "__system__",
                "title" to "当前知识库",
                "workspace_path" to source.path,
                "workspace_root_uuid" to "$prefix-rag-root",
            ))
            insert(database, "workspace_files", mapOf(
                "uuid" to "$prefix-rag-root",
                "workspace_root_uuid" to "$prefix-rag-root",
                "name" to "rag_workspace",
                "hash" to "old-rag-root",
                "is_directory" to 1,
                "physical_root_path" to source.path,
                "materialized_path" to "/",
            ))
            insert(database, "workspace_files", mapOf(
                "uuid" to "$prefix-rag-file",
                "workspace_root_uuid" to "$prefix-rag-root",
                "parent_uuid" to "$prefix-rag-root",
                "name" to "knowledge.txt",
                "hash" to digest(source.resolve("knowledge.txt")),
                "size_bytes" to source.resolve("knowledge.txt").length(),
                "is_directory" to 0,
                "mime_type" to "text/plain",
                "physical_root_path" to source.path,
                "materialized_path" to "/knowledge.txt",
            ))
        }
    }

    private fun insert(database: SQLiteDatabase, table: String, fields: Map<String, Any?>) {
        val values = ContentValues()
        database.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
            while (cursor.moveToNext()) {
                val column = cursor.getString(1)
                if (column in fields) put(values, column, fields[column])
                else if (cursor.getInt(3) != 0 && cursor.isNull(4)) {
                    when (cursor.getString(2).uppercase()) {
                        "INTEGER" -> values.put(column, 0L)
                        "REAL" -> values.put(column, 0.0)
                        "BLOB" -> values.put(column, byteArrayOf())
                        else -> values.put(column, "")
                    }
                }
            }
        }
        database.insertOrThrow(table, null, values)
    }

    private fun put(values: ContentValues, key: String, value: Any?) = when (value) {
        null -> values.putNull(key)
        is String -> values.put(key, value)
        is Int -> values.put(key, value)
        is Long -> values.put(key, value)
        is Double -> values.put(key, value)
        else -> error("测试字段类型不支持")
    }

    private fun digest(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private object EmptyPreferences : TransactionalBackupPreferenceStore {
        override suspend fun snapshot(maxTotalBytes: Long) = BackupPreferenceSnapshot(emptyList(), emptySet())
        override suspend fun preflightRestore(txId: String, before: BackupPreferenceSnapshot, after: BackupPreferenceSnapshot) = Unit
        override suspend fun prepare(txId: String, before: BackupPreferenceSnapshot, after: BackupPreferenceSnapshot) = Unit
        override suspend fun commitPrepared(txId: String) = Unit
        override suspend fun rollbackPrepared(txId: String) = Unit
        override suspend fun finalizePrepared(txId: String) = Unit
    }

    private object EmptySecrets : TransactionalBackupSecretStore {
        override suspend fun ids(): Set<SecretId> = emptySet()
        override suspend fun snapshot(ids: Set<SecretId>, maxTotalBytes: Long) = emptyMap<SecretId, ByteArray>()
        override suspend fun prepare(txId: String, before: Map<SecretId, ByteArray>, after: Map<SecretId, ByteArray>) = Unit
        override suspend fun commitPrepared(txId: String) = Unit
        override suspend fun rollbackPrepared(txId: String) = Unit
        override suspend fun finalizePrepared(txId: String) = Unit
    }

    private class IsolatedContext(base: Context, private val root: File) : ContextWrapper(base) {
        private val databases = File(root, "databases").apply { check(mkdir()) }
        private val files = File(root, "files").apply { check(mkdir()) }
        private val noBackup = File(root, "no_backup").apply { check(mkdir()) }
        override fun getApplicationContext(): Context = this
        override fun getDataDir(): File = root
        override fun getDatabasePath(name: String): File = File(databases, name)
        override fun getFilesDir(): File = files
        override fun getNoBackupFilesDir(): File = noBackup
    }

    private class AnchoredIsolatedContext(base: Context, fixture: File) : ContextWrapper(base) {
        private val databases = File(fixture, "databases").apply { check(mkdir()) }
        private val files = File(fixture, "files").apply { check(mkdir()) }
        private val noBackup = File(fixture, "no_backup").apply { check(mkdir()) }
        override fun getApplicationContext(): Context = this
        override fun getDataDir(): File = baseContext.dataDir
        override fun getDatabasePath(name: String): File = File(databases, name)
        override fun getFilesDir(): File = files
        override fun getNoBackupFilesDir(): File = noBackup
    }

    private class SystemAliasIsolatedContext(base: Context, fixture: File) : ContextWrapper(base) {
        private val fixtureRelative = base.dataDir.toPath().relativize(fixture.toPath())
        private val aliasData = Paths.get("/data/data", base.packageName).toFile()
        private val aliasFixture = aliasData.toPath().resolve(fixtureRelative).toFile()
        private val databases = File(aliasFixture, "databases").apply { check(mkdir()) }
        private val files = File(aliasFixture, "files").apply { check(mkdir()) }
        private val noBackup = File(aliasFixture, "no_backup").apply { check(mkdir()) }
        override fun getApplicationContext(): Context = this
        override fun getDataDir(): File = aliasData
        override fun getDatabasePath(name: String): File = File(databases, name)
        override fun getFilesDir(): File = files
        override fun getNoBackupFilesDir(): File = noBackup
    }
}
