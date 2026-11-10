package com.promenar.infinity.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.domain.repository.PatchOperation
import com.promenar.infinity.domain.repository.PatchResult
import com.promenar.infinity.domain.repository.ReadWindowRequest
import com.promenar.infinity.domain.repository.WriteResult
import com.promenar.infinity.infra.util.Sha256Utils
import com.promenar.infinity.data.rag.FileIndexEvent
import com.promenar.infinity.data.rag.FileIndexEventSink
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class FileOperationRepositoryTest {
    private lateinit var db: InfinityDatabase
    private lateinit var repo: FileOperationRepository
    private lateinit var testDir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = FileOperationRepository(db.fileEntryDao(), db.fileVersionDao(), TestWorkspaceFileOps())
        testDir = File(System.getProperty("user.dir"), ".infinity_test_${System.nanoTime()}")
        testDir.mkdirs()
    }

    @After
    fun tearDown() {
        db.close()
        testDir.deleteRecursively()
    }

    private suspend fun insertTestFile(
        uuid: String = "file-1",
        workspaceRootUuid: String = "root-1",
        content: String = "hello world",
        physicalBytes: ByteArray = content.toByteArray(Charsets.UTF_8),
    ): FileEntry {
        val file = File(testDir, "test.txt")
        file.writeBytes(physicalBytes)
        val hash = Sha256Utils.hashFile(file)
        db.fileEntryDao().insert(
            FileEntry(
                uuid = workspaceRootUuid,
                workspaceRootUuid = workspaceRootUuid,
                parentUuid = null,
                name = "workspace",
                hash = "test-root-identity",
                isDirectory = true,
                physicalRootPath = testDir.absolutePath,
                materializedPath = "/",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
        val entry = FileEntry(
            uuid = uuid,
            workspaceRootUuid = workspaceRootUuid,
            parentUuid = workspaceRootUuid,
            name = "test.txt",
            hash = hash,
            sizeBytes = physicalBytes.size.toLong(),
            physicalRootPath = testDir.absolutePath,
            materializedPath = "/test.txt",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        db.fileEntryDao().insert(entry)
        return entry
    }

    @Test
    fun `UTF8 BOM read显示文本不含BOM但保留物理hash`() = runBlocking<Unit> {
        val bytes = UTF8_BOM + "line1\nline2".toByteArray(Charsets.UTF_8)
        val entry = insertTestFile(content = "line1\nline2", physicalBytes = bytes)

        val result = repo.readFileRange(ROOT, entry.uuid)

        assertThat(result.content).isEqualTo("line1\nline2")
        assertThat(result.content).doesNotContain("\uFEFF")
        assertThat(result.hash).isEqualTo(Sha256Utils.hashFile(File(testDir, "test.txt")))
        assertThat(File(testDir, "test.txt").readBytes().toList()).containsExactlyElementsIn(bytes.toList()).inOrder()
    }

    @Test
    fun `read的totalLines与文本策略对CR和尾换行使用同一语义`() = runBlocking<Unit> {
        val content = "first\rsecond\r"
        val entry = insertTestFile(content = content)
        val validated = WorkspaceTextContentPolicy().validate(
            entry.name,
            entry.mimeType,
            content.toByteArray(),
            WorkspaceTextOperation.READ,
        )

        val result = repo.readFileRange(ROOT, entry.uuid)

        assertThat(result.totalLines).isEqualTo(3)
        assertThat(result.totalLines).isEqualTo(validated.lineCount)
        assertThat(result.content).isEqualTo("first\nsecond\n")
    }

    @Test
    fun `UTF8 BOM write规范化新文本并以原始bytes保存可验证历史版本`() = runBlocking<Unit> {
        val oldBytes = UTF8_BOM + "old\ncontent".toByteArray(Charsets.UTF_8)
        val entry = insertTestFile(content = "old\ncontent", physicalBytes = oldBytes)

        val result = repo.writeFileAtomic(ROOT, entry.uuid, "new\ncontent", "session", entry.hash)

        assertThat(result).isInstanceOf(WriteResult.Success::class.java)
        val current = db.fileEntryDao().getByUuid(ROOT, entry.uuid)!!
        val currentBytes = File(testDir, "test.txt").readBytes()
        assertThat(currentBytes.toList())
            .containsExactlyElementsIn("new\ncontent".toByteArray(Charsets.UTF_8).toList()).inOrder()
        assertThat(current.hash).isEqualTo(Sha256Utils.hashFile(File(testDir, "test.txt")))
        val version = db.fileVersionDao().getByFile(ROOT, entry.uuid).single()
        assertThat(File(version.contentPath).readBytes().toList()).containsExactlyElementsIn(oldBytes.toList()).inOrder()
        val diff = repo.diffFile(ROOT, entry.uuid, entry.hash)
        assertThat(diff.hunks.flatMap { it.lines }.map { it.content }).containsAtLeast("old", "new")
        assertThat(diff.hunks.flatMap { it.lines }.map { it.content }.any { '\uFEFF' in it }).isFalse()
    }

    @Test
    fun `UTF8 BOM patch以显示文本坐标修改且快照hash仍按原始bytes`() = runBlocking<Unit> {
        val oldBytes = UTF8_BOM + "line1\nline2".toByteArray(Charsets.UTF_8)
        val entry = insertTestFile(content = "line1\nline2", physicalBytes = oldBytes)

        val result = repo.patchFile(
            ROOT,
            entry.uuid,
            listOf(PatchOperation("replace_lines", startLine = 2, endLine = 2, newContent = "patched")),
            entry.hash,
        )

        assertThat(result).isInstanceOf(PatchResult.Success::class.java)
        assertThat(File(testDir, "test.txt").readBytes().toList())
            .containsExactlyElementsIn("line1\npatched".toByteArray(Charsets.UTF_8).toList()).inOrder()
        val current = db.fileEntryDao().getByUuid(ROOT, entry.uuid)!!
        assertThat(current.hash).isEqualTo(Sha256Utils.hashFile(File(testDir, "test.txt")))
        val version = db.fileVersionDao().getByFile(ROOT, entry.uuid).single()
        assertThat(version.hash).isEqualTo(entry.hash)
        assertThat(File(version.contentPath).readBytes().toList()).containsExactlyElementsIn(oldBytes.toList()).inOrder()
    }

    @Test
    fun `write is root scoped snapshots old content and diff reads history`() = runBlocking<Unit> {
        val entry = insertTestFile(content = "old content")
        val result = repo.writeFileAtomic("root-1", entry.uuid, "new content", "session-1", entry.hash)

        assertThat(result).isInstanceOf(WriteResult.Success::class.java)
        val versions = db.fileVersionDao().getByFile("root-1", entry.uuid)
        assertThat(versions).hasSize(1)
        assertThat(versions.single().hash).isEqualTo(entry.hash)
        assertThat(versions.single().workspaceRootUuid).isEqualTo("root-1")
        assertThat(File(versions.single().contentPath).readText()).isEqualTo("old content")

        val diff = repo.diffFile("root-1", entry.uuid, entry.hash)
        assertThat(diff.hunks).isNotEmpty()
        assertThat(diff.basisHash).isEqualTo(entry.hash)
    }

    @Test
    fun `会话删除期间文件写入在创建版本快照前被拒绝`() = runBlocking<Unit> {
        val entry = insertTestFile(content = "old content")
        val gate = com.promenar.infinity.data.session.SessionExecutionGate()
        repo = FileOperationRepository(
            db.fileEntryDao(), db.fileVersionDao(), TestWorkspaceFileOps(), executionGate = gate,
        )
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val deleting = async {
            gate.withDeletion("session-1", "root-1") {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        val failure = runCatching {
            repo.writeFileAtomic("root-1", entry.uuid, "new content", "session-1", entry.hash)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(com.promenar.infinity.data.session.SessionDeletingException::class.java)
        assertThat(db.fileVersionDao().getByFile("root-1", entry.uuid)).isEmpty()
        release.complete(Unit)
        deleting.await()
    }

    @Test
    fun `已进入的文件版本写入持有租约直到索引交接完成`() = runBlocking<Unit> {
        val entry = insertTestFile(content = "old content")
        val gate = com.promenar.infinity.data.session.SessionExecutionGate()
        val publishEntered = CompletableDeferred<Unit>()
        val releasePublish = CompletableDeferred<Unit>()
        repo = FileOperationRepository(
            db.fileEntryDao(),
            db.fileVersionDao(),
            TestWorkspaceFileOps(),
            indexEventSink = FileIndexEventSink {
                publishEntered.complete(Unit)
                releasePublish.await()
            },
            executionGate = gate,
        )
        val write = async {
            repo.writeFileAtomic("root-1", entry.uuid, "new content", "session-1", entry.hash)
        }
        publishEntered.await()
        var deletionEntered = false
        val deleting = async {
            gate.withDeletion("session-1", "root-1") { deletionEntered = true }
        }
        while (!gate.isDeleting("session-1")) kotlinx.coroutines.yield()

        assertThat(deletionEntered).isFalse()
        assertThat(db.fileVersionDao().getByFile("root-1", entry.uuid)).hasSize(1)

        releasePublish.complete(Unit)
        assertThat(write.await()).isInstanceOf(WriteResult.Success::class.java)
        deleting.await()
        assertThat(deletionEntered).isTrue()
    }

    @Test
    fun `diff rejects tampered historical snapshot`() = runBlocking<Unit> {
        val entry = insertTestFile(content = "old content")
        repo.writeFileAtomic("root-1", entry.uuid, "new content", "session-1", entry.hash)
        val version = db.fileVersionDao().getByFile("root-1", entry.uuid).single()
        File(version.contentPath).writeText("tampered")

        var rejected = false
        try {
            repo.diffFile("root-1", entry.uuid, entry.hash)
        } catch (_: SecurityException) {
            rejected = true
        }

        assertThat(rejected).isTrue()
    }

    @Test
    fun `patch snapshots old content only after operations validate`() = runBlocking<Unit> {
        val entry = insertTestFile(content = "line1\nline2")
        val invalid = repo.patchFile(
            "root-1",
            entry.uuid,
            listOf(PatchOperation("delete_lines", startLine = 9, endLine = 10)),
            entry.hash,
        )
        assertThat(invalid).isInstanceOf(PatchResult.Failure::class.java)
        assertThat(db.fileVersionDao().getByFile("root-1", entry.uuid)).isEmpty()

        val valid = repo.patchFile(
            "root-1",
            entry.uuid,
            listOf(PatchOperation("replace_lines", startLine = 2, endLine = 2, newContent = "changed")),
            entry.hash,
        )
        assertThat(valid).isInstanceOf(PatchResult.Success::class.java)
        val version = db.fileVersionDao().getByFile("root-1", entry.uuid).single()
        assertThat(File(version.contentPath).readText()).isEqualTo("line1\nline2")
    }

    @Test
    fun `cross root UUID is indistinguishable from not found and creates no version`() = runBlocking<Unit> {
        val entry = insertTestFile()
        assertThat(repo.writeFileAtomic("root-2", entry.uuid, "x", "session", entry.hash))
            .isEqualTo(WriteResult.NotFound)
        assertThat(db.fileVersionDao().getByFile("root-1", entry.uuid)).isEmpty()
        assertThat(File(testDir, "test.txt").readText()).isEqualTo("hello world")
    }

    @Test
    fun `physical write failure leaves database current file and versions unchanged`() = runBlocking<Unit> {
        val entry = insertTestFile(content = "stable")
        repo = FileOperationRepository(
            db.fileEntryDao(),
            db.fileVersionDao(),
            TestWorkspaceFileOps { phase ->
                if (phase == WorkspaceFilePhase.BEFORE_MUTATION) {
                    throw IllegalStateException("injected write failure")
                }
            },
        )

        var failed = false
        try {
            repo.writeFileAtomic("root-1", entry.uuid, "new", "session", entry.hash)
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertThat(failed).isTrue()
        assertThat(db.fileEntryDao().getByUuid("root-1", entry.uuid)!!.hash).isEqualTo(entry.hash)
        assertThat(File(testDir, "test.txt").readText()).isEqualTo("stable")
        assertThat(db.fileVersionDao().getByFile("root-1", entry.uuid)).isEmpty()
    }

    @Test
    fun `database commit failure rolls physical file back and removes staged snapshot`() = runBlocking<Unit> {
        val entry = insertTestFile(content = "stable")
        repo = FileOperationRepository(
            dao = db.fileEntryDao(),
            versionDao = db.fileVersionDao(),
            fileOps = TestWorkspaceFileOps(),
            versionCommitter = { _, _ -> throw IllegalStateException("injected database failure") },
        )

        var failed = false
        try {
            repo.writeFileAtomic("root-1", entry.uuid, "new", "session", entry.hash)
        } catch (_: IllegalStateException) {
            failed = true
        }

        assertThat(failed).isTrue()
        assertThat(File(testDir, "test.txt").readText()).isEqualTo("stable")
        assertThat(db.fileEntryDao().getByUuid("root-1", entry.uuid)!!.hash).isEqualTo(entry.hash)
        assertThat(db.fileVersionDao().getByFile("root-1", entry.uuid)).isEmpty()
        assertThat(File(testDir, ".infinity_versions").walkTopDown().filter { it.isFile }.toList()).isEmpty()
    }

    @Test
    fun `database failure retries a transient second-step physical rollback`() = runBlocking<Unit> {
        val entry = insertTestFile(content = "stable")
        var failRestoreOnce = true
        repo = FileOperationRepository(
            dao = db.fileEntryDao(),
            versionDao = db.fileVersionDao(),
            fileOps = TestWorkspaceFileOps { phase ->
                if (phase == WorkspaceFilePhase.ROLLBACK_BEFORE_RESTORE && failRestoreOnce) {
                    failRestoreOnce = false
                    throw IllegalStateException("injected rollback restore failure")
                }
            },
            versionCommitter = { _, _ -> throw IllegalStateException("injected database failure") },
        )

        val failure = runCatching {
            repo.writeFileAtomic("root-1", entry.uuid, "new", "session", entry.hash)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(File(testDir, "test.txt").readText()).isEqualTo("stable")
        assertThat(db.fileEntryDao().getByUuid("root-1", entry.uuid)!!.hash).isEqualTo(entry.hash)
        assertThat(File(testDir, ".infinity_versions").walkTopDown().filter { it.isFile }.toList()).isEmpty()
    }

    @Test
    fun `成功写入与patch在提交后发布带新hash的索引事件`() = runBlocking<Unit> {
        val entry = insertTestFile(content = "line1\nline2")
        val firstSinkEvents = mutableListOf<FileIndexEvent>()
        val secondSinkEvents = mutableListOf<FileIndexEvent>()
        var activeSink = FileIndexEventSink(firstSinkEvents::add)
        repo = FileOperationRepository(
            db.fileEntryDao(),
            db.fileVersionDao(),
            TestWorkspaceFileOps(),
            indexEventSink = FileIndexEventSink { event -> activeSink.publish(event) },
        )

        val write = repo.writeFileAtomic(ROOT, entry.uuid, "line1\nwrite", "session", entry.hash)
        val writeHash = (write as WriteResult.Success).newHash
        activeSink = FileIndexEventSink(secondSinkEvents::add)
        val patch = repo.patchFile(
            ROOT,
            entry.uuid,
            listOf(PatchOperation("replace_lines", startLine = 2, endLine = 2, newContent = "patch")),
            writeHash,
        ) as PatchResult.Success

        val first = firstSinkEvents.single() as FileIndexEvent.Changed
        val second = secondSinkEvents.single() as FileIndexEvent.Changed
        assertThat(first.contentHash).isEqualTo(writeHash)
        assertThat(first.targetEpoch).isGreaterThan(entry.updatedAt)
        assertThat(second.contentHash).isEqualTo(patch.newHash)
        assertThat(second.targetEpoch).isGreaterThan(first.targetEpoch)
    }

    @Test
    fun `同毫秒或时钟回拨时write与patch仍发布数据库已提交的严格递增epoch`() = runBlocking<Unit> {
        val entry = insertTestFile(content = "line1\nline2")
        val events = mutableListOf<FileIndexEvent.Changed>()
        repo = FileOperationRepository(
            db.fileEntryDao(),
            db.fileVersionDao(),
            TestWorkspaceFileOps(),
            indexEventSink = FileIndexEventSink { events += it as FileIndexEvent.Changed },
            clock = { entry.updatedAt },
        )

        val write = repo.writeFileAtomic(ROOT, entry.uuid, "line1\nwrite", "session", entry.hash)
            as WriteResult.Success
        val afterWrite = db.fileEntryDao().getByUuid(ROOT, entry.uuid)!!
        val patch = repo.patchFile(
            ROOT,
            entry.uuid,
            listOf(PatchOperation("replace_lines", startLine = 2, endLine = 2, newContent = "patch")),
            write.newHash,
        ) as PatchResult.Success
        val afterPatch = db.fileEntryDao().getByUuid(ROOT, entry.uuid)!!

        assertThat(afterWrite.updatedAt).isEqualTo(entry.updatedAt + 1)
        assertThat(afterPatch.updatedAt).isEqualTo(afterWrite.updatedAt + 1)
        assertThat(patch.targetEpoch).isEqualTo(afterPatch.updatedAt)
        assertThat(events.map { it.contentHash }).containsExactly(write.newHash, patch.newHash).inOrder()
        assertThat(events.map { it.targetEpoch })
            .containsExactly(afterWrite.updatedAt, afterPatch.updatedAt)
            .inOrder()
    }

    @Test
    fun `epoch达到上限时在任何物理或数据库写入前拒绝内容变更`() = runBlocking<Unit> {
        val entry = insertTestFile(content = "stable")
        db.fileEntryDao().update(entry.copy(updatedAt = Long.MAX_VALUE))
        repo = FileOperationRepository(
            db.fileEntryDao(),
            db.fileVersionDao(),
            TestWorkspaceFileOps(),
            clock = { 0L },
        )

        val failure = runCatching {
            repo.writeFileAtomic(ROOT, entry.uuid, "changed", "session", entry.hash)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(File(testDir, "test.txt").readText()).isEqualTo("stable")
        assertThat(db.fileEntryDao().getByUuid(ROOT, entry.uuid)?.hash).isEqualTo(entry.hash)
        assertThat(db.fileVersionDao().getByFile(ROOT, entry.uuid)).isEmpty()
    }

    @Test
    fun `写入失败冲突与相同内容不发布索引事件`() = runBlocking<Unit> {
        val entry = insertTestFile(content = "stable")
        db.fileEntryDao().update(entry.copy(vectorizedAt = entry.updatedAt))
        val events = mutableListOf<FileIndexEvent>()
        repo = FileOperationRepository(
            db.fileEntryDao(),
            db.fileVersionDao(),
            TestWorkspaceFileOps(),
            indexEventSink = FileIndexEventSink(events::add),
        )

        repo.writeFileAtomic(ROOT, entry.uuid, "stable", "session", entry.hash)
        repo.writeFileAtomic(ROOT, entry.uuid, "changed", "session", "wrong-hash")

        assertThat(events).isEmpty()
    }

    @Test
    fun `索引事件入队失败时写入结果明确标记但不伪装文件写失败`() = runBlocking<Unit> {
        val entry = insertTestFile(content = "stable")
        db.fileEntryDao().update(entry.copy(vectorizedAt = 1L, kgExtractedAt = 1L))
        repo = FileOperationRepository(
            db.fileEntryDao(),
            db.fileVersionDao(),
            TestWorkspaceFileOps(),
            indexEventSink = FileIndexEventSink { throw IllegalStateException("queue unavailable") },
        )

        val result = repo.writeFileAtomic(ROOT, entry.uuid, "committed", "session", entry.hash)

        assertThat(result).isInstanceOf(WriteResult.Success::class.java)
        result as WriteResult.Success
        assertThat(result.indexQueued).isFalse()
        assertThat(db.fileEntryDao().getByUuid(ROOT, entry.uuid)?.hash).isEqualTo(result.newHash)
        assertThat(db.fileEntryDao().getByUuid(ROOT, entry.uuid)?.vectorizedAt).isNull()
        assertThat(db.fileEntryDao().getByUuid(ROOT, entry.uuid)?.kgExtractedAt).isNull()
        assertThat(File(testDir, "test.txt").readText()).isEqualTo("committed")

        val patch = repo.patchFile(
            ROOT,
            entry.uuid,
            listOf(PatchOperation("replace_lines", startLine = 1, endLine = 1, newContent = "patched")),
            result.newHash,
        ) as PatchResult.Success
        assertThat(patch.indexQueued).isFalse()
        assertThat(patch.targetEpoch)
            .isEqualTo(db.fileEntryDao().getByUuid(ROOT, entry.uuid)?.updatedAt)
        assertThat(File(testDir, "test.txt").readText()).isEqualTo("patched")
    }

    @Test
    fun `提交后索引发布取消原样传播且保留已提交真实目标`() = runBlocking<Unit> {
        val entry = insertTestFile(content = "stable")
        val cancellation = kotlinx.coroutines.CancellationException("cancel after commit")
        repo = FileOperationRepository(
            db.fileEntryDao(),
            db.fileVersionDao(),
            TestWorkspaceFileOps(),
            indexEventSink = FileIndexEventSink {
                throw cancellation
            },
        )

        val caught = try {
            repo.writeFileAtomic(ROOT, entry.uuid, "committed", "session", entry.hash)
            null
        } catch (error: Throwable) {
            error
        }
        val committed = db.fileEntryDao().getByUuid(ROOT, entry.uuid)!!

        assertCancellationPreserved(caught, cancellation)
        assertThat(committed.hash).isEqualTo(Sha256Utils.hash("committed"))
        assertThat(committed.updatedAt).isGreaterThan(entry.updatedAt)
        assertThat(committed.vectorizedAt).isNull()
        assertThat(committed.kgExtractedAt).isNull()
        assertThat(File(testDir, "test.txt").readText()).isEqualTo("committed")
    }

    @Test
    fun `同内容write与patch在向量marker过期时重新发布当前目标`() = runBlocking<Unit> {
        val entry = insertTestFile(content = "stable")
        db.fileEntryDao().update(entry.copy(vectorizedAt = entry.updatedAt - 1L))
        val events = mutableListOf<FileIndexEvent.Changed>()
        repo = FileOperationRepository(
            db.fileEntryDao(),
            db.fileVersionDao(),
            TestWorkspaceFileOps(),
            indexEventSink = FileIndexEventSink { events += it as FileIndexEvent.Changed },
        )

        val write = repo.writeFileAtomic(ROOT, entry.uuid, "stable", "session", entry.hash)
            as WriteResult.Success
        val patch = repo.patchFile(
            ROOT,
            entry.uuid,
            listOf(PatchOperation("replace_lines", startLine = 1, endLine = 1, newContent = "stable")),
            entry.hash,
        ) as PatchResult.Success

        assertThat(write.indexQueued).isTrue()
        assertThat(write.targetEpoch).isEqualTo(entry.updatedAt)
        assertThat(patch.indexQueued).isTrue()
        assertThat(patch.targetEpoch).isEqualTo(entry.updatedAt)
        assertThat(events).containsExactly(
            FileIndexEvent.Changed(ROOT, entry.uuid, entry.hash, entry.updatedAt),
            FileIndexEvent.Changed(ROOT, entry.uuid, entry.hash, entry.updatedAt),
        ).inOrder()
        assertThat(db.fileVersionDao().getByFile(ROOT, entry.uuid)).isEmpty()
    }

    @Test
    fun `首次发布取消后同内容write可按原目标重试入队`() = runBlocking<Unit> {
        val entry = insertTestFile(content = "stable")
        val events = mutableListOf<FileIndexEvent.Changed>()
        var attempts = 0
        val cancellation = kotlinx.coroutines.CancellationException("first publish cancelled")
        repo = FileOperationRepository(
            db.fileEntryDao(),
            db.fileVersionDao(),
            TestWorkspaceFileOps(),
            indexEventSink = FileIndexEventSink { event ->
                attempts += 1
                if (attempts == 1) {
                    throw cancellation
                }
                events += event as FileIndexEvent.Changed
            },
        )

        val caught = try {
            repo.writeFileAtomic(ROOT, entry.uuid, "committed", "session", entry.hash)
            null
        } catch (error: Throwable) {
            error
        }
        val committed = db.fileEntryDao().getByUuid(ROOT, entry.uuid)!!
        val second = repo.writeFileAtomic(ROOT, entry.uuid, "committed", "session", committed.hash)
            as WriteResult.Success

        assertCancellationPreserved(caught, cancellation)
        assertThat(second.indexQueued).isTrue()
        assertThat(second.newHash).isEqualTo(committed.hash)
        assertThat(second.targetEpoch).isEqualTo(committed.updatedAt)
        assertThat(events).containsExactly(
            FileIndexEvent.Changed(ROOT, entry.uuid, committed.hash, committed.updatedAt),
        )
        assertThat(db.fileVersionDao().getByFile(ROOT, entry.uuid)).hasSize(1)
    }

    @Test
    fun `回收文件对read write patch diff均不可见且零副作用`() = runBlocking<Unit> {
        val entry = insertTestFile(content = "line1\nline2")
        db.fileEntryDao().update(entry.copy(inRecycleBin = true, recycledAt = 1L))

        assertThat(runCatching { repo.readFileRange(ROOT, entry.uuid) }.exceptionOrNull())
            .isInstanceOf(NoSuchElementException::class.java)
        assertThat(runCatching { repo.diffFile(ROOT, entry.uuid) }.exceptionOrNull())
            .isInstanceOf(NoSuchElementException::class.java)
        assertThat(repo.writeFileAtomic(ROOT, entry.uuid, "changed", "session", entry.hash))
            .isEqualTo(WriteResult.NotFound)
        val patch = repo.patchFile(
            ROOT,
            entry.uuid,
            listOf(PatchOperation("replace_lines", 1, 1, newContent = "changed")),
            entry.hash,
        ) as PatchResult.Failure

        assertThat(patch.error.code).isEqualTo("FILE_NOT_FOUND")
        assertThat(File(testDir, "test.txt").readText()).isEqualTo("line1\nline2")
        assertThat(db.fileVersionDao().getByFile(ROOT, entry.uuid)).isEmpty()
    }

    @Test
    fun `二进制 NUL 与无效UTF8对所有内容操作失败且不覆盖原文件`() = runBlocking<Unit> {
        val entry = insertTestFile(content = "placeholder")
        val binary = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0)
        File(testDir, "test.txt").writeBytes(binary)
        val binaryHash = Sha256Utils.hashFile(File(testDir, "test.txt"))
        db.fileEntryDao().update(
            entry.copy(
                name = "image.png",
                mimeType = "image/png",
                materializedPath = "/test.txt",
                hash = binaryHash,
                sizeBytes = binary.size.toLong(),
            ),
        )

        val readFailure = runCatching { repo.readFileRange(ROOT, entry.uuid) }.exceptionOrNull()
        val diffFailure = runCatching { repo.diffFile(ROOT, entry.uuid) }.exceptionOrNull()
        val writeFailure = runCatching {
            repo.writeFileAtomic(ROOT, entry.uuid, "overwrite", "session", binaryHash)
        }.exceptionOrNull()
        val patch = repo.patchFile(
            ROOT,
            entry.uuid,
            listOf(PatchOperation("insert_after", afterLine = 0, newContent = "overwrite")),
            binaryHash,
        ) as PatchResult.Failure

        listOf(readFailure, diffFailure, writeFailure).forEach { failure ->
            assertThat(failure).isInstanceOf(WorkspaceTextPolicyException::class.java)
            assertThat((failure as WorkspaceTextPolicyException).code)
                .isEqualTo(WorkspaceTextErrorCode.UNSUPPORTED_CONTENT_TYPE)
        }
        assertThat(patch.error.code).isEqualTo("UNSUPPORTED_CONTENT_TYPE")
        assertThat(File(testDir, "test.txt").readBytes().toList()).containsExactlyElementsIn(binary.toList()).inOrder()
        assertThat(db.fileVersionDao().getByFile(ROOT, entry.uuid)).isEmpty()
    }

    @Test
    fun `超出物理字节上限时在读取文件内容之前拒绝`() = runBlocking<Unit> {
        val content = "x".repeat(WorkspaceTextContentPolicy.DEFAULT_TOOL_BUDGET.maxInputBytes.toInt() + 1)
        val entry = insertTestFile(content = content)
        var physicalReads = 0
        val delegate = TestWorkspaceFileOps()
        repo = FileOperationRepository(
            db.fileEntryDao(),
            db.fileVersionDao(),
            object : WorkspaceFileOps by delegate {
                override fun read(root: java.nio.file.Path, relative: List<String>): ByteArray {
                    physicalReads += 1
                    return delegate.read(root, relative)
                }
            },
        )

        val failure = runCatching { repo.readFileRange(ROOT, entry.uuid) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(WorkspaceTextPolicyException::class.java)
        assertThat((failure as WorkspaceTextPolicyException).code)
            .isEqualTo(WorkspaceTextErrorCode.INPUT_TOO_LARGE)
        assertThat(physicalReads).isEqualTo(0)
    }

    @Test
    fun `分段读取超过1MiB的私有工作区文件只返回窗口并给出续读位置`() = runBlocking<Unit> {
        val lines = List(40_000) { "行${it + 1}:" + "z".repeat(30) }
        val content = lines.joinToString("\r\n")
        val entry = insertTestFile(content = content, physicalBytes = UTF8_BOM + content.toByteArray())
        assertThat(entry.sizeBytes).isGreaterThan(WorkspaceTextContentPolicy.DEFAULT_TOOL_BUDGET.maxInputBytes)

        val middle = repo.readFileWindow(ROOT, entry.uuid, ReadWindowRequest(startLine = 20_001, endLine = 20_500))
        val last = repo.readFileWindow(ROOT, entry.uuid, ReadWindowRequest(startLine = 39_901))

        assertThat(middle.totalLines).isEqualTo(40_000)
        assertThat(middle.content).isEqualTo(lines.subList(20_000, 20_500).joinToString("\n"))
        assertThat(middle.nextLine).isEqualTo(20_501)
        assertThat(middle.hash).isEqualTo(entry.hash)
        assertThat(last.endLine).isEqualTo(40_000)
        assertThat(last.nextLine).isNull()
        val wholeFile = runCatching { repo.readFileRange(ROOT, entry.uuid) }.exceptionOrNull()
        assertThat((wholeFile as WorkspaceTextPolicyException).code).isEqualTo(WorkspaceTextErrorCode.INPUT_TOO_LARGE)
    }

    @Test
    fun `分段读取拒绝超过100MiB上限的文件且不读取内容`() = runBlocking<Unit> {
        val entry = insertTestFile(content = "small")
        db.fileEntryDao().update(entry.copy(sizeBytes = ReadWindowRequest.MAX_FILE_BYTES + 1))

        val failure = runCatching { repo.readFileWindow(ROOT, entry.uuid, ReadWindowRequest()) }.exceptionOrNull()

        assertThat((failure as WorkspaceTextPolicyException).code).isEqualTo(WorkspaceTextErrorCode.INPUT_TOO_LARGE)
    }

    @Test
    fun `patch拒绝负坐标越界重叠与上下文不匹配且全部零副作用`() = runBlocking<Unit> {
        val fixtures = listOf(
            listOf(PatchOperation("replace_lines", startLine = -1, endLine = 1, newContent = "x")),
            listOf(PatchOperation("delete_lines", startLine = 2, endLine = 9)),
            listOf(
                PatchOperation("replace_lines", startLine = 1, endLine = 2, newContent = "x"),
                PatchOperation("delete_lines", startLine = 2, endLine = 3),
            ),
            listOf(PatchOperation("replace_lines", startLine = 1, endLine = 1, newContent = "x", expectedContent = "wrong")),
        )

        fixtures.forEachIndexed { index, operations ->
            val entry = if (index == 0) insertTestFile(content = "a\nb\nc")
            else db.fileEntryDao().getByUuid(ROOT, "file-1")!!
            val result = repo.patchFile(ROOT, entry.uuid, operations, entry.hash)
            assertThat(result).isInstanceOf(PatchResult.Failure::class.java)
            assertThat(File(testDir, "test.txt").readText()).isEqualTo("a\nb\nc")
            assertThat(db.fileVersionDao().getByFile(ROOT, entry.uuid)).isEmpty()
        }
    }

    @Test
    fun `patch仅允许afterLine等于精确EOF作为尾部插入`() = runBlocking<Unit> {
        val entry = insertTestFile(content = "a\nb")

        val beyond = repo.patchFile(
            ROOT,
            entry.uuid,
            listOf(PatchOperation("insert_after", afterLine = 3, newContent = "x")),
            entry.hash,
        )
        assertThat(beyond).isInstanceOf(PatchResult.Failure::class.java)
        assertThat(File(testDir, "test.txt").readText()).isEqualTo("a\nb")

        val exact = repo.patchFile(
            ROOT,
            entry.uuid,
            listOf(PatchOperation("insert_after", afterLine = 2, newContent = "c")),
            entry.hash,
        )
        assertThat(exact).isInstanceOf(PatchResult.Success::class.java)
        assertThat(File(testDir, "test.txt").readText()).isEqualTo("a\nb\nc")
    }

    private companion object {
        const val ROOT = "root-1"
        val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }

    private fun assertCancellationPreserved(
        caught: Throwable?,
        original: kotlinx.coroutines.CancellationException,
    ) {
        assertThat(caught).isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
        assertThat(caught?.message).isEqualTo(original.message)
        assertThat(generateSequence(caught) { it.cause }.any { it === original }).isTrue()
    }
}
