package com.promenar.infinity.data.storage

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.dao.ExternalMountDao
import com.promenar.infinity.data.local.db.entity.ExternalMountEntity
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.repository.ToolAllowlistStore
import com.promenar.infinity.data.script.ScriptFileApprovalBroker
import com.promenar.infinity.domain.repository.IFileOperationRepository
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.script.ScriptFailure
import com.promenar.infinity.domain.storage.*
import com.promenar.infinity.infra.util.Sha256Utils
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.skills.ScriptWorkspaceGateway
import io.mockk.*
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Test

class ScriptWorkspaceGatewayTest {
    private val directory = Files.createTempDirectory("script-gateway").toFile()
    private val context = mockk<Context> { every { noBackupFilesDir } returns directory }
    private var row = ExternalMountEntity("mount", "demo", "saf_tree", "content://test/tree/root", "session", false, 1)
    private val dao = mockk<ExternalMountDao> {
        coEvery { visible(any()) } answers { listOf(row).filter { it.scopeKey == firstArg<String>() } }
        coEvery { update(any()) } answers { row = firstArg() }
    }
    private val workspace = mockk<IWorkspaceRepository>()
    private val operations = mockk<IFileOperationRepository>()
    private val memory = TransferMemoryBackend()
    private val backend = object : ExternalFolderBackend by memory {
        override suspend fun read(path: String, maxBytes: Long): ByteArray {
            if (path == "/provider-large.txt") throw ExternalFolderException("TOO_LARGE", "文件超过读取上限")
            return memory.read(path, maxBytes)
        }
        override suspend fun write(path: String, content: ByteArray, expectedSha256: String?, createOnly: Boolean): ExternalFolderEntry {
            if (expectedSha256 != null && Sha256Utils.hash(memory.read(path)) != expectedSha256) throw ExternalFolderException("CONFLICT", "版本不一致")
            return memory.write(path, content, expectedSha256, createOnly)
        }
    }
    private val mounts = ExternalFolderManager(context, dao, workspace, FileIndexEventSink.None) { backend }
    private val broker = ScriptFileApprovalBroker()
    private val grants = ToolAllowlistStore(directory.resolve("grants"))
    private val execution = object : SkillExecutionContext {
        override val sessionId = "session"
        override val agentId = "agent"
        override val workspaceRootUuid = "root"
        override val workspacePath: String? = null
    }
    private suspend fun open(sessionId: String = "session") = ScriptWorkspaceGateway.open(
        object : SkillExecutionContext by execution { override val sessionId = sessionId },
        workspace, mounts, operations, FileIndexEventSink.None, broker, "/mnt/demo/main.mjs",
    )
    private fun args(path: String, content: String? = null, hash: String? = null) = buildJsonObject {
        put("path", path); content?.let { put("content", it) }; hash?.let { put("expected_sha256", it) }
    }
    private suspend fun <T> approveDelete(block: suspend () -> T): T = coroutineScope {
        val result = async { block() }
        val request = withTimeout(3000) { broker.pending.first { it.isNotEmpty() }.single() }
        assertThat(request.toolName).isEqualTo("delete_file")
        broker.resolve(request.id, approved = true)
        result.await()
    }
    @After fun cleanup() { directory.deleteRecursively() }

    @Test fun `代理拒绝路径越界和保留名称且只读挂载拒绝全部修改`() = runBlocking {
        val gateway = open()
        for (path in listOf("/mnt/demo/../outside.txt", "/mnt/demo/.infinity/x.txt", "/mnt/demo/.recycle_bin/x.txt")) {
            try { gateway.invoke("read", args(path)); error("应拒绝") } catch (_: ExternalFolderException) { }
        }
        memory.write("/a.txt", "old".toByteArray())
        row = row.copy(readOnly = true)
        for (operation in listOf("create", "mkdir", "write", "delete")) {
            val path = if (operation in setOf("create", "mkdir")) "/mnt/demo/new" else "/mnt/demo/a.txt"
            val failure = runCatching {
                if (operation == "delete") approveDelete { gateway.invoke(operation, args(path, "x", Sha256Utils.hash("old"))) }
                else gateway.invoke(operation, args(path, "x", Sha256Utils.hash("old")))
            }.exceptionOrNull()
            assertThat((failure as? ScriptFailure)?.code).isEqualTo("FILE_ERROR")
            assertThat(failure?.message).contains("知识库挂载为只读")
        }
        assertThat(String(memory.read("/a.txt"))).isEqualTo("old")
        assertThat(memory.stat("/new")).isNull()
        assertThat(gateway.invoke("read", args("/mnt/demo/a.txt")).jsonObject["content"]?.jsonPrimitive?.content).isEqualTo("old")
        assertThat(broker.pending.value).isEmpty()
    }

    @Test fun `写入免审批，删除须审批且覆盖仍须满足哈希条件`() = runBlocking {
        memory.write("/a.txt", "old".toByteArray())
        val gateway = open()
        try { gateway.invoke("write", args("/mnt/demo/a.txt", "new", "0".repeat(64))); error("应冲突") }
        catch (failure: ScriptFailure) { assertThat(failure.code).isEqualTo("FILE_ERROR") }
        assertThat(String(memory.read("/a.txt"))).isEqualTo("old")
        withTimeout(3000) {
            gateway.invoke("write", args("/mnt/demo/a.txt", "new", Sha256Utils.hash("old")))
            assertThat(String(memory.read("/a.txt"))).isEqualTo("new")
            approveDelete { gateway.invoke("delete", args("/mnt/demo/a.txt", hash = Sha256Utils.hash("new"))) }
        }
        assertThat(memory.stat("/a.txt")).isNull()
        assertThat(broker.pending.value).isEmpty()
    }

    @Test fun `脚本删除被拒绝时保留文件`() = runBlocking {
        memory.write("/a.txt", "保留".toByteArray())
        val gateway = open()
        val result = async { runCatching { gateway.invoke("delete", args("/mnt/demo/a.txt")) } }
        val request = withTimeout(3000) { broker.pending.first { it.isNotEmpty() }.single() }
        assertThat(request.toolName).isEqualTo("delete_file")
        assertThat(String(memory.read("/a.txt"))).isEqualTo("保留")
        broker.resolve(request.id, approved = false)
        assertThat((result.await().exceptionOrNull() as? ScriptFailure)?.code).isEqualTo("APPROVAL_DENIED")
        assertThat(String(memory.read("/a.txt"))).isEqualTo("保留")
    }

    @Test fun `免审批也不能使用换源后的挂载`() = runBlocking {
        memory.write("/a.txt", "old".toByteArray())
        val gateway = open()
        row = row.copy(id = "replacement")
        val failure = runCatching { gateway.invoke("write", args("/mnt/demo/a.txt", "new", Sha256Utils.hash("old"))) }.exceptionOrNull()
        assertThat((failure as? ScriptFailure)?.code).isEqualTo("MOUNT_CHANGED")
        assertThat(String(memory.read("/a.txt"))).isEqualTo("old")
        assertThat(broker.pending.value).isEmpty()
    }

    @Test fun `读取返回实际字节哈希并拒绝越根模块和大小超限`() = runBlocking {
        memory.write("/a.txt", "原文".toByteArray())
        memory.write("/large.txt", ByteArray(65537) { 65 })
        val gateway = open()
        val read = gateway.invoke("read", args("/mnt/demo/a.txt")).jsonObject
        assertThat(read["sha256"]?.jsonPrimitive?.content).isEqualTo(Sha256Utils.hash("原文"))
        try { gateway.invoke("module", args("/mnt/other/a.js")); error("应拒绝") } catch (_: ScriptFailure) { }
        try { gateway.invoke("read", args("/mnt/demo/large.txt")); error("应超限") } catch (failure: ScriptFailure) { assertThat(failure.code).isEqualTo("FILE_LIMIT") }
    }

    @Test fun `后端提前拒绝超大文件时仍返回可终止进程的资源超限错误`() = runBlocking {
        val failure = runCatching { open().invoke("read", args("/mnt/demo/provider-large.txt")) }.exceptionOrNull()
        assertThat((failure as? ScriptFailure)?.code).isEqualTo("FILE_LIMIT")
    }

    @Test fun `模块代理允许根内mjs并返回源文件正文`() = runBlocking {
        memory.write("/helper.mjs", "export const value = 42".toByteArray())
        assertThat(open().invoke("module", args("/mnt/demo/helper.mjs")).jsonPrimitive.content)
            .isEqualTo("export const value = 42")
    }

    @Test fun `普通写入免审批、不读写旧授权，其它会话不可见该挂载`() = runBlocking {
        val target = """["path","/mnt/demo/a.txt"]"""
        grants.add("write_file", target, "mount:mount", "session")
        val original = grants.entries.value
        memory.write("/a.txt", "old".toByteArray())
        withTimeout(3000) {
            open().invoke("write", args("/mnt/demo/a.txt", "one", Sha256Utils.hash("old")))
        }
        val other = runCatching { open("another").invoke("write", args("/mnt/demo/a.txt", "two", Sha256Utils.hash("one"))) }.exceptionOrNull()
        assertThat((other as? ScriptFailure)?.code).isEqualTo("MOUNT_CHANGED")
        assertThat(String(memory.read("/a.txt"))).isEqualTo("one")
        assertThat(broker.pending.value).isEmpty()
        assertThat(grants.entries.value).isEqualTo(original)
    }
}
