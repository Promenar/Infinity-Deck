package com.promenar.infinity.data.workspace

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.linux.LinuxFileSystemImpl
import com.promenar.infinity.data.linux.TestLinuxFileMetadata
import com.promenar.infinity.data.local.db.dao.FileEntryDao
import com.promenar.infinity.data.local.db.dao.WorkspaceSeqDao
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.rag.FileIndexEvent
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.repository.TestWorkspaceFileOps
import com.promenar.infinity.data.repository.WorkspaceMutationCoordinator
import com.promenar.infinity.data.repository.WorkspaceRepository
import com.promenar.infinity.data.ssh.sftpHash
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Test

/** 使用真实仓库、文件端口和磁盘，仅以内存 DAO 替换 Room。 */
class SftpPrivateWorkspaceTest {
    private val directory = Files.createTempDirectory("sftp-private").toFile().canonicalFile
    private val ops = TestWorkspaceFileOps()
    private val root = FileEntry("root", "root", null, "工作区", ops.ensureRoot(directory.toPath()),
        isDirectory = true, physicalRootPath = directory.path, materializedPath = "/", createdAt = 1, updatedAt = 1)
    private val entries = java.util.concurrent.ConcurrentHashMap<String, FileEntry>().apply { put(root.uuid, root) }
    private var onUpdate: () -> Unit = {}
    private val dao = proxy<FileEntryDao> { method, args -> when (method) {
        "getActiveByUuid", "getAnyStateByUuidForLifecycle" -> entries[args[1]]
        "getActiveByMaterializedPath" -> entries.values.firstOrNull { it.materializedPath == args[1] }
        "getAllStatesByWorkspaceRootForCleanup" -> entries.values.toList()
        "insertAbort" -> { val row = args[0] as FileEntry; check(entries.putIfAbsent(row.uuid, row) == null); Unit }
        "updateAll" -> { (args[0] as List<*>).filterIsInstance<FileEntry>().forEach { entries[it.uuid] = it }; onUpdate(); Unit }
        // 内存行从未索引：路径提交照常写入，全部文件都需要重新排队。
        "commitPathChanges" -> {
            (args[0] as List<*>).filterIsInstance<FileEntry>().forEach { check(entries.putIfAbsent(it.uuid, it) == null) }
            val updates = (args[1] as List<*>).filterIsInstance<FileEntry>()
            updates.forEach { entries[it.uuid] = it }
            onUpdate()
            updates.filterNot { it.isDirectory }
        }
        else -> error("未实现的 DAO 调用：$method")
    } }
    private val repo = WorkspaceRepository(dao, proxy<WorkspaceSeqDao> { method, _ -> error(method) }, fileOps = ops,
        deleteCommitter = { _, ids -> ids.forEach(entries::remove) })
    private val files = LinuxFileSystemImpl(directory, { true }, TestLinuxFileMetadata).asWorkspaceDirectory()
    private val events = mutableListOf<FileIndexEvent>()
    private fun destination(sink: FileIndexEventSink = FileIndexEventSink { events += it }) = SftpWorkspaceFile(repo, root.uuid, "s", "/data.bin", files, sink)
    @After fun cleanup() { directory.deleteRecursively() }

    @Test fun `下载新文件经真实仓库登记最终路径字节摘要并清理临时条目`() = runBlocking<Unit> {
        val bytes = ByteArray(131_083) { (it * 131).toByte() }
        val hash = bytes.inputStream().use { sftpHash(it, bytes.size.toLong()) }
        assertThat(destination().write(bytes.size.toLong(), false, null) { it.write(bytes); hash }).isEqualTo(hash)
        val row = entries.values.single { it.parentUuid != null }
        assertThat(row.materializedPath).isEqualTo("/data.bin")
        assertThat(row.name).isEqualTo("data.bin")
        assertThat(row.hash).isEqualTo(hash)
        assertThat(File(directory, "data.bin").readBytes()).isEqualTo(bytes)
        assertThat(directory.list()!!.filter { it.startsWith(".sftp-") }).isEmpty()
        assertThat(events.map { it.fileUuid }).containsExactly(row.uuid)
    }

    @Test fun `等待真实仓库发布锁时取消不会创建目标`() = runBlocking<Unit> {
        val locked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val prepared = CompletableDeferred<Unit>()
        var holder: Job? = null
        val task = launch(Dispatchers.IO) {
            destination().write(1, false, null, beforeCommit = {
                holder = launch(Dispatchers.IO) {
                    WorkspaceMutationCoordinator.withRoot(directory.toPath()) { locked.complete(Unit); release.await() }
                }
                runBlocking { locked.await() }
                prepared.complete(Unit)
            }) { it.write(7); byteArrayOf(7).inputStream().use { input -> sftpHash(input, 1) } }
        }
        withTimeout(5_000) { prepared.await() }
        task.cancel()
        release.complete(Unit)
        holder?.join()
        task.join()
        assertThat(File(directory, "data.bin").exists()).isFalse()
        assertThat(entries.values.any { it.materializedPath == "/data.bin" }).isFalse()
        assertThat(entries.keys).containsExactly(root.uuid)
        assertThat(directory.list()!!.filter { it.startsWith(".sftp-") }).isEmpty()
    }

    @Test fun `仓库改名事务提交后取消仍保留目标并通知已提交摘要`() = runBlocking<Unit> {
        val committed = java.util.concurrent.atomic.AtomicReference<String?>()
        val bytes = byteArrayOf(0, -1, 7)
        val hash = bytes.inputStream().use { sftpHash(it, 3) }
        val task = launch(Dispatchers.IO) {
            val worker = currentCoroutineContext().job
            onUpdate = { worker.cancel() }
            destination().write(3, false, null, onCommitted = committed::set) { it.write(bytes); hash }
        }
        task.join()
        assertThat(committed.get()).isEqualTo(hash)
        assertThat(File(directory, "data.bin").readBytes()).isEqualTo(bytes)
        assertThat(entries.values.single { it.parentUuid != null }.materializedPath).isEqualTo("/data.bin")
        assertThat(events).hasSize(1)
    }

    @Test fun `文件已提交但索引事件失败时明确反馈且不删除目标`() = runBlocking<Unit> {
        val hash = byteArrayOf(7).inputStream().use { sftpHash(it, 1) }
        val failure = org.junit.Assert.assertThrows(com.promenar.infinity.data.ssh.SshFailure::class.java) { runBlocking {
            destination(FileIndexEventSink { error("合成索引失败") }).write(1, false, null) { it.write(7); hash }
        } }
        assertThat(failure.code).isEqualTo("WORKSPACE_INDEX_FAILED")
        assertThat(File(directory, "data.bin").readBytes()).isEqualTo(byteArrayOf(7))
        assertThat(entries.values.single { it.parentUuid != null }.hash).isEqualTo(hash)
    }

    private inline fun <reified T> proxy(crossinline body: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            if (method.name == "toString") "内存 DAO" else body(method.name, args ?: emptyArray())
        } as T
}
