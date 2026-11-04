package com.promenar.infinity.data.repository

import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.backup.SecureBackupFileOps
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.local.db.entity.WorkspaceMutationEntity
import com.promenar.infinity.data.local.db.entity.WorkspaceMutationPayload
import com.promenar.infinity.data.local.db.entity.WorkspaceMutationPayloadCodec
import com.promenar.infinity.data.local.db.entity.WorkspaceMutationStage
import com.promenar.infinity.data.local.db.entity.WorkspaceMutationType
import com.promenar.infinity.infra.util.Sha256Utils
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.util.UUID
import java.security.MessageDigest

/** 发行门禁：目标 Android app 私有目录必须真实提供 descriptor-relative 文件能力。 */
class AndroidSecureWorkspaceFileOpsTest {
    private lateinit var root: Path

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = Files.createDirectory(context.filesDir.toPath().resolve("workspace-secure-${UUID.randomUUID()}"))
    }

    @After
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun moveRefusesTargetCreatedAfterAdmissionAndKeepsBothNodes() {
        val normal = SecureWorkspaceFileOps()
        WorkspaceMutationCoordinator.bindIdentityForTesting(root, normal.ensureRoot(root))
        normal.createFile(root, listOf("原文😀.txt"), "source".toByteArray())
        var armed = true
        val racing = SecureWorkspaceFileOps { phase ->
            if (armed && phase == WorkspaceFilePhase.BEFORE_MUTATION) {
                armed = false
                Files.write(root.resolve("目标😀.txt"), "new-user-data".toByteArray())
            }
        }
        val failure = runCatching { racing.move(root, listOf("原文😀.txt"), listOf("目标😀.txt")) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(java.nio.file.FileAlreadyExistsException::class.java)
        assertThat(normal.read(root, listOf("原文😀.txt")).toString(Charsets.UTF_8)).isEqualTo("source")
        assertThat(normal.read(root, listOf("目标😀.txt")).toString(Charsets.UTF_8)).isEqualTo("new-user-data")
        normal.move(root, listOf("原文😀.txt"), listOf("成功😀.txt")).commit()
        assertThat(normal.read(root, listOf("成功😀.txt")).toString(Charsets.UTF_8)).isEqualTo("source")
    }

    @Test
    fun mkdirRefusesConcurrentEmptyTargetWithoutReplacingItsIdentity() {
        val normal = SecureWorkspaceFileOps()
        WorkspaceMutationCoordinator.bindIdentityForTesting(root, normal.ensureRoot(root))
        var targetKey: String? = null
        val racing = SecureWorkspaceFileOps { phase ->
            if (phase == WorkspaceFilePhase.BEFORE_MUTATION && targetKey == null) {
                val target = Files.createDirectory(root.resolve("created"))
                targetKey = Files.readAttributes(target, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey().toString()
            }
        }
        val failure = runCatching { racing.createDirectory(root, listOf("created")) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(java.nio.file.FileAlreadyExistsException::class.java)
        assertThat(Files.readAttributes(root.resolve("created"), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey().toString())
            .isEqualTo(targetKey)
    }

    @Test
    fun mkdirRootSymlinkSwapCannotCreateEvenTemporaryOutsideNode() {
        val normal = SecureWorkspaceFileOps()
        WorkspaceMutationCoordinator.bindIdentityForTesting(root, normal.ensureRoot(root))
        val outside = Files.createDirectory(root.resolveSibling("${root.fileName}-outside"))
        val retained = root.resolveSibling("${root.fileName}-retained")
        var swapped = false
        val racing = SecureWorkspaceFileOps { phase ->
            if (phase == WorkspaceFilePhase.DESCRIPTOR_OPENED && !swapped) {
                swapped = true
                Files.move(root, retained)
                Files.createSymbolicLink(root, outside)
            }
            if (phase == WorkspaceFilePhase.DIRECTORY_TEMP_CREATED) {
                assertThat(Files.list(outside).use { it.count() }).isEqualTo(0L)
            }
        }
        try {
            assertThat(runCatching { racing.createDirectory(root, listOf("created")) }.isFailure).isTrue()
            assertThat(Files.list(outside).use { it.count() }).isEqualTo(0L)
            assertThat(Files.exists(retained.resolve("created"))).isFalse()
        } finally {
            Files.deleteIfExists(root)
            Files.move(retained, root)
            Files.delete(outside)
        }
    }

    @Test
    fun nativeFileIdentityPersistsAcrossDescriptorReopenAndRejectsReplacement() {
        val file = root.resolve("identity-probe.txt")
        Files.write(file, "unchanged-content".toByteArray())
        val token = UUID.randomUUID().toString()
        fun <T> withFd(path: Path, block: (Int) -> T): T {
            val fd = android.system.Os.open(path.toString(),
                android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_NOFOLLOW, 0)
            return try { android.os.ParcelFileDescriptor.dup(fd).use { block(it.fd) } }
            finally { android.system.Os.close(fd) }
        }
        withFd(file) { fd ->
            AndroidWorkspaceFileIdentity.create(fd, token)
            assertThat(AndroidWorkspaceFileIdentity.read(fd)).isEqualTo(token)
        }
        withFd(file) { assertThat(AndroidWorkspaceFileIdentity.read(it)).isEqualTo(token) }
        assertThat(Files.readAllBytes(file).toString(Charsets.UTF_8)).isEqualTo("unchanged-content")
        val retained = root.resolve("retained-identity-probe.txt")
        Files.move(file, retained)
        Files.write(file, "unchanged-content".toByteArray())
        withFd(file) { assertThat(AndroidWorkspaceFileIdentity.read(it)).isNull() }
        withFd(retained) { assertThat(AndroidWorkspaceFileIdentity.read(it)).isEqualTo(token) }
    }

    @Test
    fun ownerCleanupRequiresV2ProofAndExactInventory() {
        val ops = SecureWorkspaceFileOps()
        WorkspaceMutationCoordinator.bindIdentityForTesting(root, ops.ensureRoot(root))
        ops.createDirectory(root, listOf(CREATE_OWNERSHIP_DIRECTORY))
        val owner = listOf(CREATE_OWNERSHIP_DIRECTORY, "android-cleanup")
        ops.createDirectory(root, owner)
        ops.createFile(root, owner + "keep.txt", "important".toByteArray())
        assertThat(runCatching { cleanupCompletedCreateOwnership(ops, root, owner) }.isFailure).isTrue()
        assertThat(ops.read(root, owner + "keep.txt").toString(Charsets.UTF_8)).isEqualTo("important")
        ops.deleteNonRecursive(root, owner + "keep.txt")
        val staged = owner + CREATE_STAGED_NODE
        ops.createFile(root, staged, "payload".toByteArray())
        val identity = ops.inspect(root, staged)
        val token = UUID.randomUUID().toString()
        ops.retainCreationProof(root, staged, owner, token)
        ops.createFile(root, owner + CREATE_MANIFEST,
            encodeCreateOwnershipManifest(WorkspaceCreateOwnershipManifest("created.txt", identity)))
        ops.move(root, staged, listOf("created.txt")).commit()
        assertThat(runCatching { cleanupCompletedCreateOwnership(ops, root, owner) }.isFailure).isTrue()
        assertThat(ops.exists(root, owner + CREATE_MANIFEST)).isTrue()
        ops.deleteNonRecursive(root, owner + CREATE_MANIFEST)
        ops.createFile(root, owner + CREATE_MANIFEST,
            encodeCreateOwnershipManifest(WorkspaceCreateOwnershipManifest("created.txt", identity, token)))
        ops.createFile(root, owner + "keep.txt", "important".toByteArray())
        assertThat(runCatching { cleanupCompletedCreateOwnership(ops, root, owner) }.isFailure).isTrue()
        assertThat(ops.read(root, owner + "keep.txt").toString(Charsets.UTF_8)).isEqualTo("important")
        ops.deleteNonRecursive(root, owner + "keep.txt")
        cleanupCompletedCreateOwnership(ops, root, owner)
        assertThat(ops.exists(root, owner)).isFalse()
        assertThat(ops.read(root, listOf("created.txt")).toString(Charsets.UTF_8)).isEqualTo("payload")
    }

    @Test
    fun ownerCleanupReceiptSurvivesManifestRemoval() {
        val ops = SecureWorkspaceFileOps()
        WorkspaceMutationCoordinator.bindIdentityForTesting(root, ops.ensureRoot(root))
        ops.createDirectory(root, listOf(CREATE_OWNERSHIP_DIRECTORY))
        val owner = listOf(CREATE_OWNERSHIP_DIRECTORY, "android-receipt")
        ops.createDirectory(root, owner)
        ops.createDirectory(root, listOf("created"))
        val identity = ops.inspect(root, listOf("created"))
        val token = UUID.randomUUID().toString()
        ops.retainCreationProof(root, listOf("created"), owner, token)
        ops.createFile(root, owner + CREATE_MANIFEST,
            encodeCreateOwnershipManifest(WorkspaceCreateOwnershipManifest("created", identity, token)))
        val interrupted = object : WorkspaceFileOps by ops {
            override fun deleteNonRecursive(root: Path, relative: List<String>) {
                ops.deleteNonRecursive(root, relative)
                if (relative == owner + CREATE_MANIFEST) throw java.io.IOException("模拟manifest删除后中断")
            }
        }
        assertThat(runCatching { cleanupCompletedCreateOwnership(interrupted, root, owner) }.isFailure).isTrue()
        assertThat(ops.exists(root, creationCleanupReceiptPath(owner))).isTrue()
        cleanupCompletedCreateOwnership(SecureWorkspaceFileOps(), root, owner)
        assertThat(ops.exists(root, owner)).isFalse()
        assertThat(ops.exists(root, listOf("created", CREATE_DIRECTORY_MARKER))).isFalse()
        assertThat(ops.exists(root, listOf("created"))).isTrue()
    }

    @Test
    fun directoryDeletionPhaseResumesAfterMarkerRemoval() {
        val ops = SecureWorkspaceFileOps { phase ->
            if (phase == WorkspaceFilePhase.CREATION_MARKER_REMOVED) throw IllegalStateException("模拟删除阶段中断")
        }
        WorkspaceMutationCoordinator.bindIdentityForTesting(root, ops.ensureRoot(root))
        ops.createDirectory(root, listOf("owner"))
        ops.createDirectory(root, listOf("created"))
        val identity = ops.inspect(root, listOf("created"))
        val token = UUID.randomUUID().toString()
        ops.retainCreationProof(root, listOf("created"), listOf("owner"), token)
        assertThat(runCatching {
            ops.deleteCreatedNode(root, listOf("created"), listOf("owner"), token, identity)
        }.isFailure).isTrue()
        val quarantine = listOf("owner", CREATE_ROLLBACK_NODE)
        assertThat(Files.exists(root.resolve("owner/$CREATE_DIRECTORY_DELETE_READY"))).isTrue()
        SecureWorkspaceFileOps().deleteCreatedNode(root, quarantine, listOf("owner"), token, identity)
        assertThat(Files.exists(root.resolve("owner/$CREATE_ROLLBACK_NODE"))).isFalse()
    }

    @Test
    fun persistentFileIdentitySurvivesReopenAndProtectsReplacement() {
        val ops = SecureWorkspaceFileOps()
        val rootIdentity = ops.ensureRoot(root)
        WorkspaceMutationCoordinator.bindIdentityForTesting(root, rootIdentity)
        ops.createDirectory(root, listOf("owner"))
        ops.createFile(root, listOf("owner", "stage"), "payload".toByteArray())
        val identity = ops.inspect(root, listOf("owner", "stage"))
        val token = UUID.randomUUID().toString()
        ops.retainCreationProof(root, listOf("owner", "stage"), listOf("owner"), token)
        ops.move(root, listOf("owner", "stage"), listOf("created.txt")).commit()
        // 重建操作对象验证持久属性；替换后即使调用方观察到相同身份键，也须由nonce拒绝。
        val reopened = SecureWorkspaceFileOps()
        reopened.verifyCreationProof(root, listOf("created.txt"), listOf("owner"), token, identity)
        Files.move(root.resolve("created.txt"), root.resolve("retained-original.txt"))
        Files.write(root.resolve("created.txt"), "payload".toByteArray())
        val observedReplacement = identity.copy(fileKey = reopened.inspect(root, listOf("created.txt")).fileKey)
        assertThat(runCatching {
            reopened.deleteCreatedNode(root, listOf("created.txt"), listOf("owner"), token, observedReplacement)
        }.isFailure).isTrue()
        assertThat(reopened.read(root, listOf("created.txt")).toString(Charsets.UTF_8)).isEqualTo("payload")
        Files.delete(root.resolve("created.txt"))
        Files.move(root.resolve("retained-original.txt"), root.resolve("created.txt"))
        reopened.deleteCreatedNode(root, listOf("created.txt"), listOf("owner"), token, identity)
        assertThat(Files.exists(root.resolve("created.txt"))).isFalse()
    }

    @Test
    fun persistentDirectoryMarkerDoesNotAuthorizeRecursiveRollback() {
        val ops = SecureWorkspaceFileOps()
        WorkspaceMutationCoordinator.bindIdentityForTesting(root, ops.ensureRoot(root))
        ops.createDirectory(root, listOf("owner"))
        ops.createDirectory(root, listOf("created"))
        val identity = ops.inspect(root, listOf("created"))
        val token = UUID.randomUUID().toString()
        ops.retainCreationProof(root, listOf("created"), listOf("owner"), token)
        ops.createFile(root, listOf("created", "keep.txt"), "keep".toByteArray())
        assertThat(runCatching {
            SecureWorkspaceFileOps().deleteCreatedNode(root, listOf("created"), listOf("owner"), token, identity)
        }.isFailure).isTrue()
        assertThat(ops.read(root, listOf("created", "keep.txt")).toString(Charsets.UTF_8)).isEqualTo("keep")
        ops.delete(root, listOf("created", "keep.txt"))
        SecureWorkspaceFileOps().deleteCreatedNode(root, listOf("created"), listOf("owner"), token, identity)
        assertThat(Files.exists(root.resolve("created"))).isFalse()
    }

    @Test
    fun appPrivateStorageSupportsSecureDirectoryStreamAndWorkspaceLifecycle() {
        Files.newDirectoryStream(root).use { stream ->
            println("WORKSPACE_SECURE_PROVIDER=${stream.javaClass.name}")
            assertThat(stream).isInstanceOf(SecureDirectoryStream::class.java)
        }

        val ops = SecureWorkspaceFileOps()
        val identity = ops.ensureRoot(root)
        WorkspaceMutationCoordinator.bindIdentityForTesting(root, identity)
        ops.createDirectory(root, listOf("docs"))
        ops.createDirectory(root, listOf("archive"))
        ops.createFile(root, listOf("docs", "note.txt"), "old".toByteArray())
        assertThat(ops.read(root, listOf("docs", "note.txt")).toString(Charsets.UTF_8)).isEqualTo("old")

        ops.replaceFile(root, listOf("docs", "note.txt"), "new".toByteArray()).commit()
        ops.move(
            root,
            listOf("docs", "note.txt"),
            listOf("archive", "note.txt"),
        ).commit()

        assertThat(ops.read(root, listOf("archive", "note.txt")).toString(Charsets.UTF_8)).isEqualTo("new")
        assertThat(Files.exists(root.resolve("docs/note.txt"))).isFalse()
        ops.delete(root, listOf("archive", "note.txt"))
        assertThat(Files.exists(root.resolve("archive/note.txt"))).isFalse()

        ops.createFile(root, listOf("docs", "rollback.txt"), "keep".toByteArray())
        val rollbackToken = workspaceDeletionToken("android-test-rollback")
        ops.stageDelete(root, listOf("docs", "rollback.txt"), rollbackToken).rollback()
        assertThat(ops.read(root, listOf("docs", "rollback.txt")).toString(Charsets.UTF_8)).isEqualTo("keep")
        ops.stageDelete(root, listOf("docs", "rollback.txt"), rollbackToken).commit()
        assertThat(Files.exists(root.resolve("docs/rollback.txt"))).isFalse()

        ops.createFile(root, listOf("docs", "cleanup.txt"), "cleanup".toByteArray())
        ops.stageDelete(
            root,
            listOf("docs", "cleanup.txt"),
            workspaceDeletionToken("android-test-cleanup"),
        )
        ops.cleanupTombstones(root)
        // 墓碑位于工作区内部目录 .infinity/tombstones；旧版根目录墓碑清空后会被移除。
        assertThat(Files.list(root.resolve(".infinity/tombstones")).use { it.count() }).isEqualTo(0L)
        assertThat(Files.exists(root.resolve(".infinity_tombstones"))).isFalse()

        ops.createFile(root, listOf("docs", "crash.txt"), "old".toByteArray())
        val token = sha256("crash.txt".toByteArray()).take(24)
        val crashFile = root.resolve("docs/crash.txt")
        val previous = root.resolve("docs/.infinity-previous-$token")
        Files.move(crashFile, previous)
        Files.write(crashFile, "new".toByteArray())
        ops.reconcileFile(root, listOf("docs", "crash.txt"), sha256("old".toByteArray()))
        assertThat(Files.readAllBytes(crashFile).toString(Charsets.UTF_8)).isEqualTo("old")
        assertThat(Files.exists(previous)).isFalse()

        var rollbackFailures = 0
        val failingRollbackOps = SecureWorkspaceFileOps { phase ->
            if (phase == WorkspaceFilePhase.ROLLBACK_BEFORE_RESTORE) {
                rollbackFailures += 1
                throw IllegalStateException("injected persistent rollback failure")
            }
        }
        val failedRollback = failingRollbackOps.replaceFile(
            root, listOf("docs", "crash.txt"), "newer".toByteArray(),
        )
        assertThat(runCatching { failedRollback.rollback() }.isFailure).isTrue()
        assertThat(runCatching { failedRollback.rollback() }.isFailure).isTrue()
        assertThat(rollbackFailures).isEqualTo(2)
        ops.reconcileFile(root, listOf("docs", "crash.txt"), sha256("old".toByteArray()))
        assertThat(Files.readAllBytes(crashFile).toString(Charsets.UTF_8)).isEqualTo("old")

        val parked = root.resolveSibling("${root.fileName}-parked")
        Files.move(root, parked)
        val copiedNonce = Files.readAllBytes(parked.resolve(".infinity_root_identity"))
            .toString(Charsets.UTF_8).substringBefore('\n')
        Files.createDirectory(root)
        val replacementKey = Files.readAttributes(
            root,
            java.nio.file.attribute.BasicFileAttributes::class.java,
            java.nio.file.LinkOption.NOFOLLOW_LINKS,
        ).fileKey().toString()
        Files.write(root.resolve(".infinity_root_identity"), "$copiedNonce\n$replacementKey".toByteArray())
        assertThat(runCatching { ops.createDirectory(root, listOf("escape")) }.isFailure).isTrue()
        root.toFile().deleteRecursively()
        Files.move(parked, root)
    }

    @Test
    fun productionRestoreIdentitySupportsWorkspaceEnsureReadAndWrite() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SecureBackupFileOps.createTransactionRoot(root, "staging")
        val staging = root.resolve("staging")
        SecureBackupFileOps.createDirectory(staging, listOf("workspace"))
        val nonce = UUID.randomUUID().toString()
        val identity = SecureBackupFileOps.initializeWorkspaceRootIdentity(
            staging, listOf("workspace"), nonce,
        )
        SecureBackupFileOps.writeNew(staging, listOf("workspace", "a.txt"), "old".toByteArray())
        SecureBackupFileOps.moveTree(root, "staging", "restored")
        val physicalRoot = root.resolve("restored/workspace")
        val now = System.currentTimeMillis()
        val db = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            db.fileEntryDao().insert(
                FileEntry(
                    uuid = "root-restore", workspaceRootUuid = "root-restore", parentUuid = null,
                    name = "workspace", hash = identity, isDirectory = true,
                    physicalRootPath = physicalRoot.toString(), materializedPath = "/",
                    createdAt = now, updatedAt = now,
                ),
            )
            val fileHash = Sha256Utils.hash("old")
            db.fileEntryDao().insert(
                FileEntry(
                    uuid = "file-restore", workspaceRootUuid = "root-restore", parentUuid = "root-restore",
                    name = "a.txt", hash = fileHash, sizeBytes = 3,
                    physicalRootPath = physicalRoot.toString(), materializedPath = "/a.txt",
                    createdAt = now, updatedAt = now,
                ),
            )
            db.sessionDao().insert(
                SessionEntity(
                    id = "session-restore", agentId = "agent", workspacePath = physicalRoot.toString(),
                    workspaceRootUuid = "root-restore", createdAt = now, updatedAt = now,
                ),
            )
            val workspace = WorkspaceRepository(db.fileEntryDao(), db.workspaceSeqDao())
            val claimed = workspace.ensureSessionRoot("session-restore")
            assertThat(claimed.hash).isEqualTo(identity)
            val files = FileOperationRepository(db.fileEntryDao(), db.fileVersionDao())
            assertThat(files.readFileRange(claimed.uuid, "file-restore").content).isEqualTo("old")
            files.writeFileAtomic(claimed.uuid, "file-restore", "new", "session-restore", fileHash)
            assertThat(files.readFileRange(claimed.uuid, "file-restore").content).isEqualTo("new")
        } finally {
            db.close()
        }
    }

    @Test
    fun productionRecoveryBindsRealSecureRootForPreparedAndDbCommitted() = runBlocking<Unit> {
        listOf(WorkspaceMutationStage.PREPARED, WorkspaceMutationStage.DB_COMMITTED).forEachIndexed { index, stage ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val physicalRoot = Files.createDirectory(root.resolve("recovery-$index"))
            val ops = SecureWorkspaceFileOps()
            val identity = ops.ensureRoot(physicalRoot)
            val source = physicalRoot.resolve("source.txt")
            val target = physicalRoot.resolve("target.txt")
            Files.write(source, "payload".toByteArray())
            if (stage == WorkspaceMutationStage.PREPARED) Files.move(source, target)
            val now = System.currentTimeMillis()
            val rootUuid = "recovery-root-$index"
            val raw = WorkspaceMutationPayloadCodec.encode(
                WorkspaceMutationPayload(
                    sourceRelativePath = "source.txt",
                    targetRelativePath = "target.txt",
                    databaseTargetUuid = "recovery-file-$index",
                    expectedSha256 = identity,
                ),
            )
            val db = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            try {
                db.fileEntryDao().insert(
                    FileEntry(
                        uuid = rootUuid,
                        workspaceRootUuid = rootUuid,
                        parentUuid = null,
                        name = "workspace",
                        hash = identity,
                        isDirectory = true,
                        physicalRootPath = physicalRoot.toString(),
                        materializedPath = "/",
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                db.fileEntryDao().insert(
                    FileEntry(
                        uuid = "recovery-file-$index",
                        workspaceRootUuid = rootUuid,
                        parentUuid = rootUuid,
                        name = if (stage == WorkspaceMutationStage.PREPARED) "source.txt" else "target.txt",
                        hash = Sha256Utils.hash("payload"),
                        sizeBytes = "payload".toByteArray().size.toLong(),
                        physicalRootPath = physicalRoot.toString(),
                        materializedPath = if (stage == WorkspaceMutationStage.PREPARED) "/source.txt" else "/target.txt",
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                db.workspaceMutationDao().insert(
                    WorkspaceMutationEntity(
                        operationId = "recovery-op-$index",
                        workspaceRootUuid = rootUuid,
                        operationType = WorkspaceMutationType.MOVE,
                        payload = raw,
                        payloadDigest = Sha256Utils.hash(raw),
                        state = stage,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )

                WorkspaceFileMutationRecoveryCoordinator(db, ops).recoverOrThrow()

                if (stage == WorkspaceMutationStage.PREPARED) {
                    assertThat(Files.readAllBytes(source).toString(Charsets.UTF_8)).isEqualTo("payload")
                    assertThat(Files.exists(target)).isFalse()
                } else {
                    assertThat(Files.exists(source)).isFalse()
                    assertThat(Files.readAllBytes(target).toString(Charsets.UTF_8)).isEqualTo("payload")
                }
                assertThat(db.workspaceMutationDao().get("recovery-op-$index")).isNull()
            } finally {
                db.close()
            }
        }
    }

    @Test(timeout = 180_000)
    fun productionRoomDirectoryRenameAndDeleteUseConstantTimeCleanReconcile() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var contentReads = 0
        val ops = SecureWorkspaceFileOps { phase ->
            if (phase == WorkspaceFilePhase.RECONCILE_CONTENT_READ) contentReads += 1
        }
        val identity = ops.ensureRoot(root)
        WorkspaceMutationCoordinator.bindIdentityForTesting(root, identity)
        ops.createDirectory(root, listOf("tree"))
        val now = System.currentTimeMillis()
        val db = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            db.fileEntryDao().insert(
                FileEntry(
                    uuid = "root-perf", workspaceRootUuid = "root-perf", parentUuid = null,
                    name = "workspace", hash = identity, isDirectory = true,
                    physicalRootPath = root.toString(), materializedPath = "/",
                    createdAt = now, updatedAt = now,
                ),
            )
            db.fileEntryDao().insert(
                FileEntry(
                    uuid = "tree", workspaceRootUuid = "root-perf", parentUuid = "root-perf",
                    name = "tree", hash = "", isDirectory = true,
                    physicalRootPath = root.toString(), materializedPath = "/tree",
                    createdAt = now, updatedAt = now,
                ),
            )
            repeat(1_000) { index ->
                val name = "f-$index.txt"
                ops.createFile(root, listOf("tree", name), byteArrayOf(1))
                db.fileEntryDao().insert(
                    FileEntry(
                        uuid = "file-$index", workspaceRootUuid = "root-perf", parentUuid = "tree",
                        name = name, hash = Sha256Utils.hash(byteArrayOf(1).toString(Charsets.UTF_8)),
                        sizeBytes = 1, physicalRootPath = root.toString(), materializedPath = "/tree/$name",
                        createdAt = now, updatedAt = now,
                    ),
                )
            }
            val sparse = root.resolve("tree/large.bin")
            java.nio.channels.FileChannel.open(
                sparse, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE,
            ).use {
                it.position(512L * 1024 * 1024 - 1)
                it.write(java.nio.ByteBuffer.wrap(byteArrayOf(0)))
            }
            db.fileEntryDao().insert(
                FileEntry(
                    uuid = "large", workspaceRootUuid = "root-perf", parentUuid = "tree",
                    name = "large.bin", hash = "clean-path-does-not-read", sizeBytes = 512L * 1024 * 1024,
                    physicalRootPath = root.toString(), materializedPath = "/tree/large.bin",
                    createdAt = now, updatedAt = now,
                ),
            )
            val workspace = WorkspaceRepository(
                db.fileEntryDao(),
                db.workspaceSeqDao(),
                fileOps = ops,
                deleteCommitter = WorkspaceDeletionTransaction(db)::delete,
            )

            workspace.rename("root-perf", "tree", "renamed")
            assertThat(contentReads).isEqualTo(0)
            assertThat(Files.exists(root.resolve("renamed/large.bin"))).isTrue()
            assertThat(db.fileEntryDao().getByUuid("root-perf", "file-999")!!.materializedPath)
                .isEqualTo("/renamed/f-999.txt")
            val renamedFiles = db.fileEntryDao().getSubtree("root-perf", "/renamed", false)
                .filterNot { it.isDirectory }
            assertThat(renamedFiles).hasSize(1_001)
            assertThat(renamedFiles.all { it.materializedPath.startsWith("/renamed/") }).isTrue()

            workspace.permanentDelete("root-perf", "tree")
            workspace.permanentDelete("root-perf", "tree")
            assertThat(contentReads).isEqualTo(0)
            assertThat(Files.exists(root.resolve("renamed"))).isFalse()
            assertThat(db.fileEntryDao().getByUuid("root-perf", "large")).isNull()
            assertThat(db.fileEntryDao().getSubtree("root-perf", "/", false).filterNot { it.isDirectory }).isEmpty()
        } finally {
            db.close()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
