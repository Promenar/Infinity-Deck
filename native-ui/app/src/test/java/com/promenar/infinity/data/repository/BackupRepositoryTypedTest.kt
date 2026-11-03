package com.promenar.infinity.data.repository

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.backup.BackupContent
import com.promenar.infinity.data.backup.BackupDataSource
import com.promenar.infinity.data.backup.BackupExportOptions
import com.promenar.infinity.data.backup.BackupManifest
import com.promenar.infinity.data.backup.BackupPackageCodec
import com.promenar.infinity.data.backup.BackupSnapshot
import com.promenar.infinity.data.backup.BackupValidationException
import com.promenar.infinity.data.backup.PendingRestoreMetadata
import com.promenar.infinity.data.backup.PendingRestoreStore
import com.promenar.infinity.data.backup.ValidatedBackup
import com.promenar.infinity.data.backup.DefaultBackupPackageCodec
import com.promenar.infinity.data.backup.PendingRestorePayload
import com.promenar.infinity.data.backup.PendingRestorePhase
import com.promenar.infinity.data.backup.SkillBackupPartition
import com.promenar.infinity.data.backup.SkillBackupMetadata
import com.promenar.infinity.data.backup.SkillBackupSummary
import com.promenar.infinity.data.backup.SkippedSkillGitRepository
import com.promenar.infinity.data.backup.SkillGitRemote
import com.promenar.infinity.data.remote.webdav.RemoteBackup
import com.promenar.infinity.data.remote.webdav.UploadAndPruneResult
import com.promenar.infinity.data.remote.webdav.WebDavBackupClient
import com.promenar.infinity.data.remote.webdav.WebDavConfig
import com.promenar.infinity.data.remote.webdav.WebDavPruneWarning
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue

class BackupRepositoryTypedTest {
    @Test
    fun `缓存根或祖先为链接时拒绝且不清理链接目标`() = runBlocking {
        val root = java.nio.file.Files.createTempDirectory("backup-spool-link")
        try {
            val victim = java.nio.file.Files.createDirectory(root.resolve("victim"))
            val sentinel = java.nio.file.Files.write(victim.resolve("preserved.txt"), "保留".toByteArray())
            val link = java.nio.file.Files.createSymbolicLink(root.resolve("linked"), victim)
            listOf(link, link.resolve("nested")).forEach { path ->
                val repository = BackupRepository(RecordingSource(), RecordingCodec(), FakeWebDav(), FakePendingStore(), cacheDirectory = path)
                var rejected = false
                try { repository.exportTo(java.io.ByteArrayOutputStream()) } catch (_: Exception) { rejected = true }
                assertThat(rejected).isTrue()
                assertThat(java.nio.file.Files.readAllBytes(sentinel).toString(Charsets.UTF_8)).isEqualTo("保留")
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun `export is canonical and secrets are disabled by default`() = runBlocking {
        val source = RecordingSource()
        val codec = RecordingCodec()
        val repository = BackupRepository(source, codec, FakeWebDav(), FakePendingStore())

        val bytes = repository.export(BackupExportOptions())

        assertThat(source.lastContent).containsExactly(
            BackupContent.DATABASE,
            BackupContent.PREFERENCES,
            BackupContent.FILES,
        )
        assertThat(codec.includeSecrets).isFalse()
        assertThat(bytes).isEqualTo(byteArrayOf(9, 8, 7))
        assertThat(source.snapshot.database).isEqualTo(byteArrayOf(0, 0, 0))
    }

    @Test
    fun `secret export requires matching non-empty password and wipes owned copies`() = runBlocking {
        val source = RecordingSource()
        val codec = RecordingCodec()
        val repository = BackupRepository(source, codec, FakeWebDav(), FakePendingStore())

        assertThrows<BackupValidationException> {
            runBlocking {
                repository.export(
                    BackupExportOptions(
                        includeSecrets = true,
                        password = "one".toCharArray(),
                        passwordConfirmation = "two".toCharArray(),
                    )
                )
            }
        }

        val password = "same".toCharArray()
        val confirmation = "same".toCharArray()
        repository.export(BackupExportOptions(true, password, confirmation))
        assertThat(codec.passwordObservedAfterReturn).isEqualTo(CharArray(4))
        assertThat(password.concatToString()).isEqualTo("same")
        assertThat(confirmation.concatToString()).isEqualTo("same")
    }

    @Test
    fun `core data can be encrypted without exporting secrets`() = runBlocking {
        val password = "core-only".toCharArray()
        val encoded = BackupRepository(
            RecordingSource(),
            DefaultBackupPackageCodec(),
            FakeWebDav(),
            FakePendingStore(),
        ).export(
            BackupExportOptions(
                includeSecrets = false,
                password = password,
                passwordConfirmation = password.copyOf(),
                encryptPackage = true,
            ),
        )

        try {
            assertThat(encoded.copyOfRange(0, 4)).isEqualTo("NXBK".encodeToByteArray())
            DefaultBackupPackageCodec().decode(encoded, "core-only".toCharArray()).use { restored ->
                assertThat(restored.manifest.encrypted).isTrue()
                assertThat(restored.manifest.containsSecrets).isFalse()
            }
            assertThrows<BackupValidationException> {
                DefaultBackupPackageCodec().decode(encoded, "wrong".toCharArray())
            }
        } finally {
            encoded.fill(0)
            password.fill('\u0000')
        }
    }

    @Test
    fun `upload preserves committed warning and only uses uploadAndPrune`() = runBlocking {
        val warning = WebDavPruneWarning("warning", listOf("old.infinity"))
        val webDav = FakeWebDav(warning)
        val repository = BackupRepository(RecordingSource(), RecordingCodec(), webDav, FakePendingStore())

        val result = repository.upload(WebDavConfig("https://dav.invalid/backups/", "u", "p"), BackupExportOptions())

        assertThat(result.pruneWarning).isEqualTo(warning)
        assertThat(webDav.keep).isEqualTo(5)
        assertThat(webDav.uploadedBytes).isEqualTo(byteArrayOf(9, 8, 7))
    }

    @Test
    fun `real codec export stages only authenticated package and local owned bytes are wiped`() = runBlocking {
        val pending = RecordingPendingStore()
        val repository = BackupRepository(RecordingSource(), DefaultBackupPackageCodec(), FakeWebDav(), pending)
        val encoded = repository.export()

        repository.stageLocalRestore(ByteArrayInputStream(encoded), "borrowed".toCharArray())

        assertThat(pending.stageCalls).isEqualTo(1)
        assertThat(pending.packageReference).isEqualTo(ByteArray(encoded.size))
        assertThat(pending.passwordReference).isEqualTo(CharArray("borrowed".length))
        encoded.fill(0)
    }

    @Test
    fun `wrong password and corrupt package never create pending restore`() = runBlocking {
        val pending = RecordingPendingStore()
        val repository = BackupRepository(RecordingSource(), DefaultBackupPackageCodec(), FakeWebDav(), pending)
        val password = "correct".toCharArray()
        val encoded = repository.export(BackupExportOptions(true, password, password.copyOf()))
        try {
            assertThrows<BackupValidationException> {
                repository.stageValidated(encoded, "wrong".toCharArray())
            }
            assertThrows<BackupValidationException> {
                repository.stageValidated(encoded.copyOf().also { it[it.lastIndex]++ }, password)
            }
            assertThat(pending.stageCalls).isEqualTo(0)
        } finally {
            encoded.fill(0)
            password.fill('\u0000')
        }
    }

    @Test
    fun `local input above sixteen MiB is rejected before pending write`() = runBlocking {
        val pending = RecordingPendingStore()
        val repository = BackupRepository(RecordingSource(), RecordingCodec(), FakeWebDav(), pending)
        val oversized = ByteArray(com.promenar.infinity.data.backup.BackupPackageLimits.MAX_IN_MEMORY_BYTES.toInt() + 1)

        assertThrows<BackupValidationException> {
            repository.stageLocalRestore(ByteArrayInputStream(oversized))
        }
        assertThat(pending.stageCalls).isEqualTo(0)
        oversized.fill(0)
    }

    @Test
    fun `remote restore download validation and pending stage all stay off caller thread`() = runBlocking {
        val caller = Thread.currentThread()
        val observedThreads = ConcurrentLinkedQueue<Thread>()
        val remote = RemoteBackup("remote.infinity", 3, 1, null)
        val config = WebDavConfig("https://dav.invalid/", "u", "p")
        val codec = object : BackupPackageCodec {
            override fun encode(snapshot: BackupSnapshot, options: com.promenar.infinity.data.backup.BackupOptions) = error("unused")
            override fun decode(bytes: ByteArray, password: CharArray?): ValidatedBackup {
                observedThreads += Thread.currentThread()
                return ValidatedBackup(BackupManifest(1, 1, "test", 1, emptyList(), false, false))
            }
        }
        val webDav = object : WebDavBackupClient {
            override suspend fun test(config: WebDavConfig) = Result.success(Unit)
            override suspend fun uploadAtomic(config: WebDavConfig, fileName: String, bytes: ByteArray) = error("unused")
            override suspend fun uploadAndPrune(config: WebDavConfig, fileName: String, bytes: ByteArray, keep: Int) = error("unused")
            override suspend fun list(config: WebDavConfig): List<RemoteBackup> {
                observedThreads += Thread.currentThread()
                return listOf(remote)
            }
            override suspend fun download(config: WebDavConfig, backup: RemoteBackup): ByteArray {
                observedThreads += Thread.currentThread()
                return byteArrayOf(1, 2, 3)
            }
            override suspend fun prune(config: WebDavConfig, keep: Int) = error("unused")
        }
        val pending = object : PendingRestoreStore {
            override fun begin(expectedTxId: String) { observedThreads += Thread.currentThread() }
            override fun stage(expectedTxId: String, packageBytes: ByteArray, password: CharArray?): PendingRestoreMetadata {
                observedThreads += Thread.currentThread()
                return PendingRestoreMetadata(
                    expectedTxId,
                    MessageDigest.getInstance("SHA-256").digest(packageBytes),
                    PendingRestorePhase.STAGING,
                )
            }
            override fun authorize(expectedTxId: String) = error("unused")
            override fun read(): PendingRestorePayload? = null
            override fun clear(expectedTxId: String) = Unit
            override fun cancel(expectedTxId: String) = Unit
        }

        BackupRepository(RecordingSource(), codec, webDav, pending)
            .stageRemoteRestore("remote-op", config, remote, null)

        assertThat(observedThreads).isNotEmpty()
        assertThat(observedThreads.none { it === caller }).isTrue()
    }

    @Test
    fun `本地与 WebDAV 导出包含相同技能分区并返回跳过统计`() = runBlocking {
        val summary = SkillBackupSummary(1, 2, listOf(SkippedSkillGitRepository(".",
            listOf(SkillGitRemote("origin", "https://example.invalid/repo.git")), "main", "a".repeat(40))))
        fun source() = RecordingSource(BackupSnapshot(
            database = byteArrayOf(1), preferences = byteArrayOf(2), databaseSchemaVersion = 5,
            appVersion = "test", createdAt = 1,
            skills = SkillBackupPartition(mapOf("alpha/SKILL.md" to "技能".toByteArray()),
                SkillBackupMetadata(listOf("alpha"), summary)),
        ))
        val codec = DefaultBackupPackageCodec()
        val webDav = FakeWebDav()
        val local = BackupRepository(source(), codec, webDav, FakePendingStore()).exportWithSummary()
        val receipt = BackupRepository(source(), codec, webDav, FakePendingStore())
            .upload(WebDavConfig("https://dav.invalid/", "synthetic", "synthetic"))
        assertThat(local.skills).isEqualTo(summary)
        assertThat(receipt.skills).isEqualTo(summary)
        assertThat(webDav.uploadedBytes).isEqualTo(local.packageBytes)
        codec.decode(webDav.uploadedBytes, null).use {
            assertThat(it.skills?.files?.get("alpha/SKILL.md")).isEqualTo("技能".toByteArray())
            assertThat(it.skills?.metadata?.summary).isEqualTo(summary)
        }
        val localPending = RecordingPendingStore()
        val remotePending = RecordingPendingStore()
        webDav.downloadBytes = webDav.uploadedBytes.copyOf()
        BackupRepository(source(), codec, webDav, localPending)
            .stageLocalRestore("local-skill-op", ByteArrayInputStream(local.packageBytes))
        BackupRepository(source(), codec, webDav, remotePending)
            .stageRemoteRestore("remote-skill-op", WebDavConfig("https://dav.invalid/", "synthetic", "synthetic"), webDav.list(WebDavConfig("https://dav.invalid/", "synthetic", "synthetic")).single())
        assertThat(localPending.stagedCopy).isEqualTo(remotePending.stagedCopy)
        codec.decode(remotePending.stagedCopy!!, null).use {
            assertThat(it.skills?.metadata?.summary).isEqualTo(summary)
        }
    }

    private class RecordingSource(val snapshot: BackupSnapshot = BackupSnapshot(byteArrayOf(1, 2, 3), byteArrayOf(4), databaseSchemaVersion = 1, appVersion = "test")) : BackupDataSource {
        var lastContent: Set<BackupContent>? = null
        override suspend fun snapshot(content: Set<BackupContent>): BackupSnapshot = snapshot.also { lastContent = content }
        override suspend fun restore(validated: ValidatedBackup) = error("unused")
        override suspend fun recoverInterruptedRestore() = Unit
    }

    private class RecordingCodec : BackupPackageCodec {
        var includeSecrets = false
        var passwordObservedAfterReturn: CharArray? = null
        override fun encode(snapshot: BackupSnapshot, options: com.promenar.infinity.data.backup.BackupOptions): ByteArray {
            includeSecrets = options.includeSecrets
            passwordObservedAfterReturn = options.password
            return byteArrayOf(9, 8, 7)
        }
        override fun decode(bytes: ByteArray, password: CharArray?): ValidatedBackup = error("unused")
    }

    private class FakeWebDav(private val warning: WebDavPruneWarning? = null) : WebDavBackupClient {
        var keep = 0
        var uploadedBytes = ByteArray(0)
        var downloadBytes: ByteArray? = null
        override suspend fun test(config: WebDavConfig) = Result.success(Unit)
        override suspend fun uploadAtomic(config: WebDavConfig, fileName: String, bytes: ByteArray) = error("must not be called")
        override suspend fun uploadAndPrune(config: WebDavConfig, fileName: String, bytes: ByteArray, keep: Int): UploadAndPruneResult {
            this.keep = keep
            uploadedBytes = bytes.copyOf()
            return UploadAndPruneResult.Committed(fileName, warning)
        }
        override suspend fun list(config: WebDavConfig): List<RemoteBackup> = downloadBytes?.let { listOf(RemoteBackup("skill.infinity", it.size.toLong(), 1, null)) }.orEmpty()
        override suspend fun download(config: WebDavConfig, backup: RemoteBackup): ByteArray = downloadBytes?.copyOf() ?: error("unused")
        override suspend fun prune(config: WebDavConfig, keep: Int) = error("must not be called")
    }

    private class FakePendingStore : PendingRestoreStore {
        override fun begin(expectedTxId: String) = Unit
        override fun stage(expectedTxId: String, packageBytes: ByteArray, password: CharArray?) = error("unused")
        override fun authorize(expectedTxId: String) = error("unused")
        override fun read() = null
        override fun clear(expectedTxId: String) = Unit
        override fun cancel(expectedTxId: String) = Unit
    }

    private class RecordingPendingStore : PendingRestoreStore {
        var stageCalls = 0
        var packageReference: ByteArray? = null
        var stagedCopy: ByteArray? = null
        var passwordReference: CharArray? = null
        override fun begin(expectedTxId: String) = Unit
        override fun stage(expectedTxId: String, packageBytes: ByteArray, password: CharArray?): PendingRestoreMetadata {
            stageCalls++
            packageReference = packageBytes
            stagedCopy = packageBytes.copyOf()
            passwordReference = password
            return PendingRestoreMetadata(
                expectedTxId,
                MessageDigest.getInstance("SHA-256").digest(packageBytes),
                PendingRestorePhase.STAGING,
            )
        }
        override fun authorize(expectedTxId: String) = PendingRestoreMetadata(
            expectedTxId,
            MessageDigest.getInstance("SHA-256").digest(byteArrayOf(1)),
            PendingRestorePhase.STAGED,
        )
        override fun read(): PendingRestorePayload? = null
        override fun clear(expectedTxId: String) = Unit
        override fun cancel(expectedTxId: String) = Unit
    }
}
