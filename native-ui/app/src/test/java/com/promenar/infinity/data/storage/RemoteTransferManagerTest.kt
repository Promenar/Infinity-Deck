package com.promenar.infinity.data.storage

import com.promenar.infinity.data.ssh.SFTP_MOUNT_CHUNK_BYTES
import com.promenar.infinity.data.ssh.SshFailure
import com.promenar.infinity.data.ssh.sftpPartPath
import com.promenar.infinity.domain.storage.*
import com.promenar.infinity.domain.storage.RemoteTransferDirection.UPLOAD
import com.promenar.infinity.domain.storage.RemoteTransferState.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteTransferManagerTest {
    @get:Rule val directory = TemporaryFolder()
    private val bytes = ByteArray(SFTP_MOUNT_CHUNK_BYTES * 2 + 11) { ((it / 177 + 11 * it) % 251).toByte() }
    private val transport = MemoryMountTransport()

    private fun source() = File(directory.root.canonicalFile, "source").apply { writeBytes(bytes) }

    private fun request(file: File, remote: String = "/mount/data") =
        RemoteTransferRequest("mount-id", "nas", "/mnt/drive/${remote.substringAfterLast('/')}", remote, file.path)

    private fun manager(journal: RemoteTransferJournal = RemoteTransferJournal(directory.root), activity: suspend (String, Boolean) -> Unit = { _, _ -> }) =
        RemoteTransferManager(journal, { RemoteTransferEndpoint(transport, "known-host", "/mount", false) }, registerActivity = activity)

    @Test fun 上传暂停续传与不覆盖发布的所有分支() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        transport.onAppend = { if (it == 0L && !entered.isCompleted) { entered.complete(Unit); gate.await() } }
        val file = source()
        manager().use { manager ->
            val task = manager.enqueueUpload(request(file))
            withTimeout(5_000) { entered.await() }
            manager.pause(task.id)
            gate.complete(Unit)
            val paused = manager.awaitTask(task.id, 5_000)!!
            assertEquals(PAUSED, paused.state)
            val temp = sftpPartPath(task.remoteFullPath, task.id)
            assertEquals(SFTP_MOUNT_CHUNK_BYTES.toLong(), paused.bytesTransferred)
            assertArrayEquals(bytes.copyOf(SFTP_MOUNT_CHUNK_BYTES), transport.bytes(temp))
            manager.resume(task.id)
            val done = manager.awaitTask(task.id, 5_000)!!
            assertEquals(SUCCEEDED, done.state)
            assertEquals(mountTestHash(bytes), done.receipt?.sha256)
            assertEquals("ssh_exec_sha256sum", done.receipt?.verificationMethod)
            assertArrayEquals(bytes, transport.bytes(task.remoteFullPath))
            assertEquals(listOf(0L, SFTP_MOUNT_CHUNK_BYTES.toLong(), SFTP_MOUNT_CHUNK_BYTES * 2L), transport.appendOffsets)
            withTimeout(5_000) { while (transport.bytes(temp) != null) delay(5) }

            val repeat = manager.enqueueUpload(request(file))
            assertTrue(manager.awaitTask(repeat.id, 5_000)!!.receipt!!.deduplicated)
            transport.put(task.remoteFullPath, byteArrayOf(9))
            val conflict = manager.enqueueUpload(request(file))
            assertEquals("TARGET_CONFLICT", manager.awaitTask(conflict.id, 5_000)?.errorCode)
            assertArrayEquals(byteArrayOf(9), transport.bytes(task.remoteFullPath))
            transport.hardlink = false
            val unsupported = manager.enqueueUpload(request(file, "/mount/other"))
            assertEquals("ATOMIC_PUBLISH_UNSUPPORTED", manager.awaitTask(unsupported.id, 5_000)?.errorCode)
            assertNull(transport.bytes("/mount/other"))
            manager.clearFinished()
            assertTrue(manager.tasks.value.isEmpty())
        }
    }

    @Test fun 下载暂停续传源变化与取消删除部分文件() = runBlocking<Unit> {
        transport.put("/mount/data", bytes)
        val target = File(directory.root.canonicalFile, "download")
        manager().use { manager ->
            suspend fun pauseFirstChunk(target: File): RemoteTransferTask {
                val entered = CompletableDeferred<Unit>()
                val gate = CompletableDeferred<Unit>()
                transport.onRead = { if (entered.complete(Unit)) runBlocking { gate.await() } }
                val task = manager.enqueueDownload(request(target))
                withTimeout(5_000) { entered.await() }
                manager.pause(task.id)
                gate.complete(Unit)
                return manager.awaitTask(task.id, 5_000)!!.also { assertEquals(PAUSED, it.state) }
            }
            val paused = pauseFirstChunk(target)
            val part = File(target.parentFile, ".infinity-part-${paused.id}.tmp")
            assertEquals(SFTP_MOUNT_CHUNK_BYTES.toLong(), part.length())
            manager.resume(paused.id)
            val done = manager.awaitTask(paused.id, 5_000)!!
            assertEquals(SUCCEEDED, done.state)
            assertEquals(mountTestHash(bytes), done.receipt?.sha256)
            assertArrayEquals(bytes, target.readBytes())
            assertEquals(listOf(0L, SFTP_MOUNT_CHUNK_BYTES.toLong()), transport.readOffsets)

            val another = pauseFirstChunk(File(target.parentFile, "changed"))
            transport.put("/mount/data", bytes.reversedArray())
            manager.resume(another.id)
            assertEquals("SOURCE_CHANGED", manager.awaitTask(another.id, 5_000)?.errorCode)
            val remaining = File(target.parentFile, ".infinity-part-${another.id}.tmp")
            assertTrue(remaining.isFile)
            manager.cancel(another.id)
            assertEquals(CANCELLED, manager.awaitTask(another.id, 5_000)?.state)
            withTimeout(5_000) { while (remaining.exists()) delay(5) }
        }
    }

    @Test fun 失败断点重试检查精确偏移与损坏偏移重传() = runBlocking<Unit> {
        val file = source()
        for (corrupt in listOf(false, true)) {
            val journal = RemoteTransferJournal(directory.newFolder())
            manager(journal).use { manager ->
                transport.appendOffsets.clear()
                val target = if (corrupt) "/mount/corrupt" else "/mount/retry"
                var fail = true
                transport.onAppend = {
                    if (it == SFTP_MOUNT_CHUNK_BYTES.toLong() && fail) {
                        fail = false
                        throw SshFailure("TIMEOUT")
                    }
                }
                val task = manager.enqueueUpload(request(file, target))
                assertEquals("TIMEOUT", manager.awaitTask(task.id, 5_000)?.errorCode)
                if (corrupt) transport.put(sftpPartPath(target, task.id), byteArrayOf(1))
                manager.retry(task.id)
                assertEquals(SUCCEEDED, manager.awaitTask(task.id, 5_000)?.state)
                assertEquals(if (corrupt) 0L else SFTP_MOUNT_CHUNK_BYTES.toLong(), transport.appendOffsets[2])
                assertArrayEquals(bytes, transport.bytes(target))
            }
        }
    }

    @Test fun 创建回执丢失后检查点识别已创建的空临时文件() = runBlocking<Unit> {
        val file = source()
        var failedOnce = false
        transport.afterCreate = {
            if (!failedOnce) { failedOnce = true; throw SshFailure("TIMEOUT") }
            error("续传应识别已创建的空临时文件")
        }
        manager().use { manager ->
            val task = manager.enqueueUpload(request(file))
            assertEquals("TIMEOUT", manager.awaitTask(task.id, 5_000)?.errorCode)
            assertArrayEquals(byteArrayOf(), transport.bytes(sftpPartPath(task.remoteFullPath, task.id)))
            manager.retry(task.id)
            assertEquals(SUCCEEDED, manager.awaitTask(task.id, 5_000)?.state)
            assertArrayEquals(bytes, transport.bytes(task.remoteFullPath))
        }
    }

    @Test fun 发布回执丢失时保持对账且不会误报取消() = runBlocking<Unit> {
        transport.afterPublish = {
            transport.statFailure = "TIMEOUT"
            throw SshFailure("TIMEOUT")
        }
        manager().use { manager ->
            val task = manager.enqueueUpload(request(source()))
            withTimeout(5_000) {
                manager.tasks.first { rows -> rows.any { it.id == task.id && it.state == RECONCILING && it.errorCode == "TIMEOUT" } }
            }
            manager.cancel(task.id)
            assertEquals(RECONCILING, manager.tasks.value.single().state)
            assertArrayEquals(bytes, transport.bytes(task.remoteFullPath))
            transport.statFailure = null
            withTimeout(5_000) {
                // 等当前对账释放调度槽，再提交重试。
                while (manager.tasks.value.single().state == RECONCILING) {
                    manager.retry(task.id)
                    delay(10)
                }
            }
            assertEquals(SUCCEEDED, manager.awaitTask(task.id, 5_000)?.state)
            assertEquals(mountTestHash(bytes), manager.tasks.value.single().receipt?.sha256)
        }
    }

    @Test fun 重启会暂停传输并对账已提交目标() = runBlocking<Unit> {
        val journal = RemoteTransferJournal(directory.root)
        val file = source()
        lateinit var task: RemoteTransferTask
        manager(journal).use { manager ->
            task = manager.enqueueUpload(request(file))
            assertEquals(SUCCEEDED, manager.awaitTask(task.id, 5_000)?.state)
            withTimeout(5_000) { while (transport.bytes(sftpPartPath(task.remoteFullPath, task.id)) != null) delay(5) }
        }
        val saved = journal.load().single()
        val interrupted = saved.copy(task = task.copy(id = "paused-fixture", state = TRANSFERRING), verifiedSha256 = null, verificationMethod = null)
        val committing = saved.copy(task = saved.task.copy(state = COMMITTING, receipt = null), cleanupPending = false)
        journal.save(listOf(interrupted, committing))
        val recoveryEntered = CompletableDeferred<Unit>()
        val recoveryProceed = CompletableDeferred<Unit>()
        RemoteTransferManager(journal, {
            recoveryEntered.complete(Unit)
            recoveryProceed.await()
            RemoteTransferEndpoint(transport, "known-host", "/mount", false)
        }).use { manager ->
            withTimeout(5_000) { recoveryEntered.await() }
            assertEquals("PROCESS_INTERRUPTED", manager.tasks.value.single { it.id == "paused-fixture" }.errorCode)
            assertEquals(PAUSED, manager.tasks.value.single { it.id == "paused-fixture" }.state)
            assertEquals(RECONCILING, manager.tasks.value.single { it.id == task.id }.state)
            recoveryProceed.complete(Unit)
            assertEquals(SUCCEEDED, manager.awaitTask(task.id, 5_000)?.state)
            assertTrue(manager.awaitTask(task.id, 0)?.receipt?.deduplicated == true)
        }
    }

    @Test fun 并发全局两个每主机一个且暂停后释放活动() = runBlocking<Unit> {
        val hold = CompletableDeferred<Unit>()
        val active = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val maximum = java.util.concurrent.atomic.AtomicInteger()
        val busy = MemoryMountTransport().apply { onAppend = { hold.await() } }
        val file = source()
        val journal = RemoteTransferJournal(directory.root)
        RemoteTransferManager(journal, { RemoteTransferEndpoint(busy, it.hostAlias, "/mount", false) },
            registerActivity = { id, enabled ->
                if (enabled) {
                    active.add(id)
                    maximum.accumulateAndGet(active.size, ::maxOf)
                } else active.remove(id)
            }).use { manager ->
            val a1 = manager.enqueueUpload(request(file, "/mount/a1"))
            val a2 = manager.enqueueUpload(request(file, "/mount/a2"))
            val b = manager.enqueueUpload(request(file, "/mount/b").copy(hostAlias = "nas-b"))
            val c = manager.enqueueUpload(request(file, "/mount/c").copy(hostAlias = "nas-c"))
            withTimeout(5_000) { manager.tasks.first { rows -> rows.count { it.state == TRANSFERRING } == 2 } }
            assertEquals(setOf(a1.id, b.id), active.toSet())
            assertEquals(QUEUED, manager.tasks.value.single { it.id == a2.id }.state)
            assertEquals(QUEUED, manager.tasks.value.single { it.id == c.id }.state)
            manager.pauseAll()
            hold.complete(Unit)
            for (id in listOf(a1.id, a2.id, b.id, c.id)) assertEquals(PAUSED, manager.awaitTask(id, 5_000)?.state)
            withTimeout(5_000) { while (active.isNotEmpty()) delay(5) }
            assertEquals(2, maximum.get())
        }
    }
}
