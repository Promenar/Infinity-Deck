package com.promenar.infinity.data.local.db.recovery

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.backup.RestoreJournalAuthenticator
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest

class DualDatabaseRecoveryJournalTest {
    @Test
    fun `工作区发布回执只接受带版本的nonce与inode身份`() {
        val root = Files.createTempDirectory("dual-db-journal-root-identity")
        try {
            val journal = FileDualDatabaseRecoveryJournal(root, DigestAuthenticator)
            val identity = "nx1:11111111-2222-4333-8444-555555555555|" + "a".repeat(64)
            val publication = RecoveryTreePublication(
                role = "current", activeWorkspace = true, ownerSessionId = "session",
                snapshotTransactionId = "tx-workspace", sourcePath = "/files/workspace",
                sourceRootIdentity = "b".repeat(64), sourceTreeSha256 = "c".repeat(64),
                targetRelativePath = "workspaces/root", targetRootIdentity = identity,
            )
            journal.write(record().copy(treePublications = listOf(publication)))
            assertThat(journal.read()?.treePublications?.single()?.targetRootIdentity).isEqualTo(identity)
            assertThat(runCatching {
                journal.write(record().copy(treePublications = listOf(publication.copy(targetRootIdentity = identity.removePrefix("nx1:")))))
            }.isFailure).isTrue()
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun `数据库移动回执拒绝含设备号的fileKey`() {
        val root = Files.createTempDirectory("dual-db-journal-move-identity")
        try {
            val journal = FileDualDatabaseRecoveryJournal(root, DigestAuthenticator)
            val identity = "nx1:11111111-2222-4333-8444-555555555555|" + "a".repeat(64)
            val move = RecoveryMoveReceipt(CURRENT_DATABASE_NAME, CURRENT_DATABASE_NAME, "b".repeat(64), 10, identity, false)
            journal.write(record().copy(databaseMoves = listOf(move)))
            assertThat(journal.read()?.databaseMoves?.single()?.sourceFileKey).isEqualTo(identity)
            assertThat(runCatching {
                journal.write(record().copy(databaseMoves = listOf(move.copy(sourceFileKey = "(dev=abc,ino=123)"))))
            }.isFailure).isTrue()
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun `journal round trips and rejects unknown fields`() {
        val root = Files.createTempDirectory("dual-db-journal")
        try {
            val journal = FileDualDatabaseRecoveryJournal(root, DigestAuthenticator)
            journal.write(record())
            assertThat(journal.read()).isEqualTo(record())
            val file = root.resolve(".dual-database-recovery-v1.json")
            Files.write(
                file,
                (Files.readAllBytes(file).toString(Charsets.UTF_8).dropLast(1) + ",\"unknown\":true}")
                    .toByteArray(Charsets.UTF_8),
            )
            assertThat(runCatching { journal.read() }.isFailure).isTrue()
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun `journal rejects writer gate before complete and traversal paths`() {
        val root = Files.createTempDirectory("dual-db-journal")
        try {
            val journal = FileDualDatabaseRecoveryJournal(root, DigestAuthenticator)
            assertThat(runCatching { journal.write(record().copy(writerGateOpened = true)) }.isFailure).isTrue()
            val bad = source("legacy", LEGACY_DATABASE_NAME, 17, PUBLIC_V17_IDENTITY)
                .copy(files = listOf(RecoveryFileFingerprint("../escape", 1, "a".repeat(64))))
            assertThat(runCatching { journal.write(record().copy(legacy = bad)) }.isFailure).isTrue()
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun `staged journal requires an authenticated working attempt`() {
        val root = Files.createTempDirectory("dual-db-journal")
        try {
            val journal = FileDualDatabaseRecoveryJournal(root, DigestAuthenticator)
            val staged = record().copy(
                stage = DualDatabaseRecoveryStage.STAGED,
                workingLegacySha256 = "c".repeat(64),
                workingCurrentSha256 = "d".repeat(64),
            )
            assertThat(runCatching { journal.write(staged) }.isFailure).isTrue()
            journal.write(staged.copy(workingAttemptId = "attempt-fixed-test"))
            assertThat(journal.read()?.workingAttemptId).isEqualTo("attempt-fixed-test")
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun `发布journal接受v18至v34可信候选并拒绝未知身份`() {
        val root = Files.createTempDirectory("dual-db-journal-schema")
        try {
            val journal = FileDualDatabaseRecoveryJournal(root, DigestAuthenticator)
            val publishing = record().copy(
                stage = DualDatabaseRecoveryStage.DB_PUBLISHING,
                workingAttemptId = "attempt-candidate",
                workingLegacySha256 = "c".repeat(64),
                workingCurrentSha256 = "d".repeat(64),
                treeArchivesComplete = true,
                candidateRelativePath = "candidate.db",
                candidateSha256 = "e".repeat(64),
                candidateCommitMarker = "dual-database-recovery-v1:tx-1",
                idMappingSha256 = "f".repeat(64),
            )
            listOf(CURRENT_V18_IDENTITY, CURRENT_V19_IDENTITY, CURRENT_V20_IDENTITY, CURRENT_V21_IDENTITY,
                CURRENT_V22_IDENTITY, CURRENT_V23_IDENTITY, CURRENT_V24_IDENTITY, CURRENT_V25_IDENTITY,
                CURRENT_V26_IDENTITY, CURRENT_V27_IDENTITY, CURRENT_V28_IDENTITY, CURRENT_V29_IDENTITY, CURRENT_V30_IDENTITY, CURRENT_V31_IDENTITY, CURRENT_V32_IDENTITY, CURRENT_V33_IDENTITY, CURRENT_V34_IDENTITY, CURRENT_V35_IDENTITY).forEach { identity ->
                journal.write(publishing.copy(candidateRoomIdentity = identity))
                assertThat(journal.read()?.candidateRoomIdentity).isEqualTo(identity)
            }
            assertThat(runCatching {
                journal.write(publishing.copy(candidateRoomIdentity = "untrusted-room-identity"))
            }.isFailure).isTrue()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun record() = DualDatabaseRecoveryRecord(
        transactionId = "tx-1",
        stage = DualDatabaseRecoveryStage.SNAPSHOTTED,
        legacy = source("legacy", LEGACY_DATABASE_NAME, 17, PUBLIC_V17_IDENTITY),
        current = source("current", CURRENT_DATABASE_NAME, 2, PUBLIC_V2_IDENTITY),
        snapshotManifestSha256 = "b".repeat(64),
    )

    private fun source(role: String, name: String, version: Int, identity: String): RecoveryDatabaseSource {
        val files = listOf(RecoveryFileFingerprint(name, 1, "a".repeat(64)))
        return RecoveryDatabaseSource(role, name, version, identity, files, recoveryManifestSha256(files))
    }

    private object DigestAuthenticator : RestoreJournalAuthenticator {
        override fun sign(payload: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(payload)
        override fun verify(payload: ByteArray, signature: ByteArray): Boolean =
            MessageDigest.isEqual(sign(payload), signature)
    }
}
