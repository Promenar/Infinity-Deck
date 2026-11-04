package com.promenar.infinity.data.storage

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.domain.storage.ExternalFolderEntry
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.domain.storage.ExternalMount
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

class ExternalTreeTransferTest {
    @Test fun `目标被用户替换类型或重建时不能当作副本删除`() = runBlocking {
        for (directory in listOf(true, false)) {
            val target = TransferMemoryBackend()
            val source = TransferMemoryBackend().apply {
                write("/a.txt", "same".toByteArray())
                failDelete = true
                beforeDelete = {
                    target.delete("/a.txt")
                    if (directory) target.createDirectory("/a.txt") else target.write("/a.txt", "same".toByteArray())
                }
            }
            val failure = assertThrows(ExternalFolderException::class.java) {
                runBlocking { ExternalTreeTransfer.move(source, "/a.txt", target, "/a.txt") }
            }
            assertThat(failure.code).isEqualTo("RECOVERY_REQUIRED")
            assertThat(target.stat("/a.txt")!!.isDirectory).isEqualTo(directory)
            assertThat(source.stat("/a.txt")).isNotNull()
        }
    }
    @Test fun `移动使用的快照必须符合调用方的版本`() = runBlocking {
        val source = TransferMemoryBackend().apply { write("/a.txt", "newer".toByteArray()) }
        val destination = TransferMemoryBackend()
        assertThrows(ExternalFolderException::class.java) {
            runBlocking { ExternalTreeTransfer.move(source, "/a.txt", destination, "/a.txt", "0".repeat(64)) }
        }
        assertThat(String(source.read("/a.txt"))).isEqualTo("newer")
        assertThat(destination.stat("/a.txt")).isNull()
    }
    @Test fun `目标回读不可信时保留双方供恢复`() = runBlocking {
        val source = TransferMemoryBackend().apply { write("/a.txt", "before".toByteArray()) }
        val destination = TransferMemoryBackend().apply { corruptRead = true }
        val failure = assertThrows(ExternalFolderException::class.java) {
            runBlocking { ExternalTreeTransfer.move(source, "/a.txt", destination, "/a.txt") }
        }
        assertThat(String(source.read("/a.txt"))).isEqualTo("before")
        assertThat(failure.code).isEqualTo("RECOVERY_REQUIRED")
        assertThat(destination.stat("/a.txt")).isNotNull()
    }

    @Test fun `删除源失败时回滚目标`() = runBlocking {
        val source = TransferMemoryBackend().apply { write("/a.txt", "a".toByteArray()); failDelete = true }
        val destination = TransferMemoryBackend()
        assertThrows(ExternalFolderException::class.java) {
            runBlocking { ExternalTreeTransfer.move(source, "/a.txt", destination, "/a.txt") }
        }
        assertThat(source.stat("/a.txt")).isNotNull()
        assertThat(destination.stat("/a.txt")).isNull()
    }

    @Test fun `目标已存在不能覆盖也不能回滚用户数据`() = runBlocking {
        val source = TransferMemoryBackend().apply { write("/a.txt", "source".toByteArray()) }
        val destination = TransferMemoryBackend().apply { write("/a.txt", "user".toByteArray()) }
        assertThrows(ExternalFolderException::class.java) {
            runBlocking { ExternalTreeTransfer.move(source, "/a.txt", destination, "/a.txt") }
        }
        assertThat(String(destination.read("/a.txt"))).isEqualTo("user")
        assertThat(String(source.read("/a.txt"))).isEqualTo("source")
    }

    @Test fun `复制验证成功后才删除源并保持目录内容`() = runBlocking {
        val source = TransferMemoryBackend().apply { createDirectory("/dir"); write("/dir/a.txt", byteArrayOf(1, 2)) }
        val destination = TransferMemoryBackend()
        ExternalTreeTransfer.move(source, "/dir", destination, "/copy")
        assertThat(source.stat("/dir")).isNull()
        assertThat(destination.read("/copy/a.txt")).isEqualTo(byteArrayOf(1, 2))
    }

    @Test fun `别名可见范围冲突与挂载uuid解析均绑定会话`() {
        val mount = ExternalMount("id", "termux", "saf_tree", "content://test/tree/root", "session", 1)
        assertThat(ExternalMountPaths.parse("/mnt/termux/a.txt")).isEqualTo("termux" to "/a.txt")
        assertThat(ExternalMountPaths.uuidPath(ExternalMountPaths.uuid(mount, "/a.txt"), listOf(mount)))
            .isEqualTo("/mnt/termux/a.txt")
        assertThat(ExternalMountPaths.uuidPath(ExternalMountPaths.uuid(mount, "/a.txt"), emptyList())).isNull()
        assertThat(ExternalMountPaths.conflicts(mount, mount.copy(id = "other"))).isTrue()
        assertThat(ExternalMountPaths.conflicts(mount, mount.copy(id = "other", sessionId = "different"))).isFalse()
        assertThrows(ExternalFolderException::class.java) { ExternalMountPaths.parse("/mnt/termux/../private") }
        assertThrows(ExternalFolderException::class.java) { ExternalMountPaths.validateAlias(".infinity-secret") }
    }
}

internal class TransferMemoryBackend : ExternalFolderBackend {
    private val files = linkedMapOf("/" to null as ByteArray?)
    private val identities = mutableMapOf("/" to "root")
    private var nextIdentity = 0
    var corruptRead = false
    var failDelete = false
    var beforeDelete: (suspend () -> Unit)? = null
    override suspend fun stat(path: String) = if (files.containsKey(path)) ExternalFolderEntry(
        path, path.substringAfterLast('/'), files[path] == null, files[path]?.size?.toLong() ?: 0, 1, identity = identities[path],
    ) else null
    override suspend fun list(path: String) = files.keys.filter { it != path && it.substringBeforeLast('/').ifEmpty { "/" } == path }
        .map { stat(it)!! }
    override suspend fun read(path: String, maxBytes: Long): ByteArray = files[path]!!.copyOf().let { if (corruptRead) it + 0 else it }
    override suspend fun write(path: String, content: ByteArray, expectedSha256: String?, createOnly: Boolean): ExternalFolderEntry {
        if (createOnly && files.containsKey(path)) throw ExternalFolderException("CONFLICT", "已存在")
        if (!files.containsKey(path)) identities[path] = (++nextIdentity).toString()
        files[path] = content.copyOf()
        return stat(path)!!
    }
    override suspend fun createDirectory(path: String): ExternalFolderEntry {
        if (files.containsKey(path)) throw ExternalFolderException("CONFLICT", "已存在")
        files[path] = null
        identities[path] = (++nextIdentity).toString()
        return stat(path)!!
    }
    override suspend fun deleteForTransfer(path: String, expected: ExternalFolderEntry, expectedSha256: String?) {
        beforeDelete?.invoke()
        if (failDelete) throw ExternalFolderException("IO", "删除失败")
        val current = stat(path) ?: throw ExternalFolderException("CONFLICT", "来源不存在")
        if (current.identity != expected.identity || current.isDirectory != expected.isDirectory ||
            current.isDirectory && list(path).isNotEmpty() || !current.isDirectory && externalSha256(files[path]!!) != expectedSha256) throw ExternalFolderException("CONFLICT", "来源变化")
        files.remove(path); identities.remove(path)
    }
    override suspend fun delete(path: String, expectedSha256: String?) {
        beforeDelete?.invoke()
        if (failDelete) throw ExternalFolderException("IO", "删除失败")
        files.keys.filter { it == path || it.startsWith("$path/") }.toList().forEach { files.remove(it); identities.remove(it) }
    }
}
