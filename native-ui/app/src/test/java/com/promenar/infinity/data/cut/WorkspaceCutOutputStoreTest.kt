package com.promenar.infinity.data.cut

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.production.CutCompositionPlan
import com.promenar.infinity.data.production.CutExportRecord
import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.domain.storage.ExternalFolderEntry
import com.promenar.infinity.domain.storage.ExternalFolderException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WorkspaceCutOutputStoreTest {
    @TempDir lateinit var temporary: File

    private val videoPath = "project/cuts/S1E01-v1.mp4"
    private val subtitlePath = "project/cuts/S1E01-v1.srt"
    private val videoBytes = "已完成的成片".toByteArray()
    private val subtitles = "1\n00:00:00,000 --> 00:00:01,000\n字幕\n"

    private fun plan() = CutCompositionPlan(
        width = 1920,
        height = 1080,
        durationSeconds = 1.0,
        videoPath = videoPath,
        subtitlePath = subtitlePath,
        burnSubtitles = false,
        srt = subtitles,
    )

    private fun video() = File(temporary, "completed.mp4").apply { writeBytes(videoBytes) }
    private fun cache() = File(temporary, "cache").apply { mkdirs() }
    private fun store(backend: MemoryBackend, afterWrite: suspend () -> Unit = {}) =
        WorkspaceCutOutputStore(cache(), { sessionId, path ->
            assertThat(sessionId).isEqualTo("session")
            assertThat(path).isAnyOf(videoPath, subtitlePath)
            CutOutputTarget(backend, afterWrite)
        }, now = { 123L })

    @Test fun `首次发布先完成两文件再登记且所有媒体通过流式接口传输`() = runBlocking {
        val backend = MemoryBackend()
        var confirmations = 0
        var registered: CutExportRecord? = null

        val result = store(backend) { confirmations++ }.publish("session", plan(), video()) { record ->
            assertThat(backend.text(videoPath)).isEqualTo(videoBytes.toString(Charsets.UTF_8))
            assertThat(backend.text(subtitlePath)).isEqualTo(subtitles)
            registered = record
        }

        assertThat(result).isEqualTo(CutExportRecord(videoPath, subtitlePath, 1.0, videoBytes.size.toLong(), 123L))
        assertThat(registered).isEqualTo(result)
        assertThat(confirmations).isEqualTo(2)
        assertThat(backend.streamWrites).isEqualTo(2)
        assertThat(cache().listFiles().orEmpty()).isEmpty()
    }

    @Test fun `重复导出以身份及摘要删除旧文件并发布新文件`() = runBlocking {
        val backend = MemoryBackend().apply { oldOutputs() }
        val oldIdentities = listOf(backend.stat("/$videoPath")!!.identity, backend.stat("/$subtitlePath")!!.identity)

        store(backend).publish("session", plan(), video()) { }

        assertThat(backend.text(videoPath)).isEqualTo(videoBytes.toString(Charsets.UTF_8))
        assertThat(backend.text(subtitlePath)).isEqualTo(subtitles)
        assertThat(backend.deletions.map { it.second }).containsExactlyElementsIn(oldIdentities).inOrder()
        assertThat(backend.deletions.all { it.third != null }).isTrue()
        assertThat(cache().listFiles().orEmpty()).isEmpty()
    }

    @Test fun `没有字幕时也发布固定路径的空SRT`() = runBlocking {
        val backend = MemoryBackend()

        val result = store(backend).publish("session", plan().copy(srt = ""), video()) { }

        assertThat(result.subtitlePath).isEqualTo(subtitlePath)
        assertThat(backend.stat("/$subtitlePath")!!.size).isEqualTo(0L)
    }

    @Test fun `挂载项目从最近实体目录创建cuts而不访问聚合根`() = runBlocking {
        val memory = MemoryBackend().apply { put("mnt/library/project/keep.txt", "已有项目") }
        val backend = object : ExternalFolderBackend by memory {
            override suspend fun stat(path: String): ExternalFolderEntry? {
                check(path != "/mnt") { "挂载聚合根不可直接访问" }
                return memory.stat(path)
            }
        }
        val plan = plan().copy(videoPath = "mnt/library/project/cuts/S1E01-v1.mp4", subtitlePath = "mnt/library/project/cuts/S1E01-v1.srt")
        val store = WorkspaceCutOutputStore(cache(), { _, _ -> CutOutputTarget(backend) }, now = { 123L })
        store.publish("session", plan, video()) { }
        assertThat(memory.stat("/${plan.videoPath}")).isNotNull()
        assertThat(memory.stat("/${plan.subtitlePath}")).isNotNull()
    }

    @Test fun `首次发布第二文件失败时清理第一文件及失败文件`() = runBlocking {
        val backend = MemoryBackend().apply { failWritePath = "/$subtitlePath" }
        var registered = false

        val failure = failure { store(backend).publish("session", plan(), video()) { registered = true } }

        assertThat(failure).isInstanceOf(ExternalFolderException::class.java)
        assertThat((failure as ExternalFolderException).code).isEqualTo("IO")
        assertThat(backend.stat("/$videoPath")).isNull()
        assertThat(backend.stat("/$subtitlePath")).isNull()
        assertThat(registered).isFalse()
        assertThat(cache().listFiles().orEmpty()).isEmpty()
    }

    @Test fun `替换时第二文件失败恢复两份旧文件`() = runBlocking {
        val backend = MemoryBackend().apply { oldOutputs(); failWritePath = "/$subtitlePath" }

        failure { store(backend).publish("session", plan(), video()) { error("不得登记") } }

        assertOldOutputs(backend)
        assertThat(cache().listFiles().orEmpty()).isEmpty()
    }

    @Test fun `登记失败整体恢复旧文件并保留原始异常`() = runBlocking {
        val backend = MemoryBackend().apply { oldOutputs() }
        val registrationFailure = IllegalStateException("登记冲突")

        val failure = failure { store(backend).publish("session", plan(), video()) { throw registrationFailure } }

        assertThat(failure).isSameInstanceAs(registrationFailure)
        assertOldOutputs(backend)
        assertThat(cache().listFiles().orEmpty()).isEmpty()
    }

    @Test fun `首次发布登记失败清理两份新文件`() = runBlocking {
        val backend = MemoryBackend()

        failure { store(backend).publish("session", plan(), video()) { error("登记失败") } }

        assertThat(backend.stat("/$videoPath")).isNull()
        assertThat(backend.stat("/$subtitlePath")).isNull()
    }

    @Test fun `登记挂起时取消也在不可取消上下文恢复旧文件`() = runBlocking {
        val backend = MemoryBackend().apply { oldOutputs() }
        val registering = CompletableDeferred<Unit>()
        val job = launch {
            store(backend).publish("session", plan(), video()) {
                registering.complete(Unit)
                awaitCancellation()
            }
        }
        registering.await()

        job.cancelAndJoin()

        assertThat(job.isCancelled).isTrue()
        assertOldOutputs(backend)
        assertThat(cache().listFiles().orEmpty()).isEmpty()
    }

    @Test fun `写入期间取消传播取消并恢复旧文件`() = runBlocking {
        val backend = MemoryBackend().apply {
            oldOutputs()
            writeFailure = CancellationException("写入取消")
            failWritePath = "/$subtitlePath"
        }

        val failure = failure { store(backend).publish("session", plan(), video()) { error("不得登记") } }

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertOldOutputs(backend)
    }

    @Test fun `后端已写成功但receipt尚未交付时取消仍能恢复旧文件`() = runBlocking {
        val published = CompletableDeferred<Unit>()
        val releaseReceipt = CompletableDeferred<Unit>()
        val backend = MemoryBackend().apply {
            oldOutputs()
            afterStreamWrite = { path ->
                if (path == "/$videoPath") {
                    published.complete(Unit)
                    releaseReceipt.await()
                }
            }
        }
        val job = launch { store(backend).publish("session", plan(), video()) { error("不得登记") } }
        published.await()

        job.cancel()
        releaseReceipt.complete(Unit)
        job.join()

        assertThat(job.isCancelled).isTrue()
        assertOldOutputs(backend)
        assertThat(cache().listFiles().orEmpty()).isEmpty()
    }

    @Test fun `不可取消的登记已经提交后取消保持新文件`() = runBlocking {
        val registering = CompletableDeferred<Unit>()
        val releaseRegistration = CompletableDeferred<Unit>()
        val backend = MemoryBackend().apply { oldOutputs() }
        var registered: CutExportRecord? = null
        val job = launch {
            store(backend).publish("session", plan(), video()) { record ->
                withContext(NonCancellable) {
                    registering.complete(Unit)
                    releaseRegistration.await()
                    registered = record
                }
            }
        }
        registering.await()

        job.cancel()
        releaseRegistration.complete(Unit)
        job.join()

        assertThat(job.isCancelled).isTrue()
        assertThat(registered?.videoPath).isEqualTo(videoPath)
        assertThat(backend.text(videoPath)).isEqualTo(videoBytes.toString(Charsets.UTF_8))
        assertThat(backend.text(subtitlePath)).isEqualTo(subtitles)
        assertThat(cache().listFiles().orEmpty()).isEmpty()
    }

    @Test fun `备份后旧文件被同身份外部修改时条件删除拒绝覆盖`() = runBlocking {
        val backend = MemoryBackend().apply {
            oldOutputs()
            afterRead = { path ->
                if (path == "/$subtitlePath") {
                    afterRead = null
                    put(videoPath, "外部修改", keepIdentity = true)
                }
            }
        }

        failure { store(backend).publish("session", plan(), video()) { error("不得登记") } }

        assertThat(backend.text(videoPath)).isEqualTo("外部修改")
        assertThat(backend.text(subtitlePath)).isEqualTo("旧字幕")
    }

    @Test fun `登记期间新文件被同内容新身份替换时回滚保留外部文件和恢复备份`() = runBlocking {
        val backend = MemoryBackend().apply { oldOutputs() }

        val failure = failure {
            store(backend).publish("session", plan(), video()) {
                backend.put(videoPath, videoBytes.toString(Charsets.UTF_8))
                error("登记失败")
            }
        }

        assertThat((failure as ExternalFolderException).code).isEqualTo("RECOVERY_REQUIRED")
        assertThat(backend.text(videoPath)).isEqualTo(videoBytes.toString(Charsets.UTF_8))
        assertThat(backend.text(subtitlePath)).isEqualTo("旧字幕")
        val recovery = cache().listFiles().orEmpty().single()
        assertThat(failure.message).contains(recovery.absolutePath)
        assertThat(File(recovery, "original-0.bin").readText()).isEqualTo("旧视频")
        assertThat(File(recovery, "recovery.properties").readText()).contains("original-0.bin")
    }

    @Test fun `回滚恢复失败保留本地旧文件以便人工恢复`() = runBlocking {
        val backend = MemoryBackend().apply { oldOutputs() }

        val failure = failure {
            store(backend).publish("session", plan(), video()) {
                backend.failWritePath = "/$videoPath"
                error("登记失败")
            }
        }

        assertThat((failure as ExternalFolderException).code).isEqualTo("RECOVERY_REQUIRED")
        assertThat(backend.stat("/$videoPath")).isNull()
        assertThat(backend.text(subtitlePath)).isEqualTo("旧字幕")
        val recovery = cache().listFiles().orEmpty().single()
        assertThat(File(recovery, "original-0.bin").readText()).isEqualTo("旧视频")
        assertThat(failure.suppressed).isNotEmpty()
    }

    @Test fun `后端失败留下归属不明的条目时保留条目和恢复信息`() = runBlocking {
        val backend = MemoryBackend().apply {
            oldOutputs()
            failWritePath = "/$subtitlePath"
            leaveUnknownOnFailure = true
        }

        val failure = failure { store(backend).publish("session", plan(), video()) { error("不得登记") } }

        assertThat((failure as ExternalFolderException).code).isEqualTo("RECOVERY_REQUIRED")
        assertThat(backend.text(videoPath)).isEqualTo("旧视频")
        assertThat(backend.text(subtitlePath)).isEqualTo(subtitles)
        assertThat(cache().listFiles().orEmpty()).hasLength(1)
    }

    @Test fun `私有文件确认失败也作为事务失败补偿`() = runBlocking {
        val backend = MemoryBackend().apply { oldOutputs() }
        var confirmation = 0

        failure {
            store(backend) {
                confirmation++
                if (confirmation == 2) error("确认失败")
            }.publish("session", plan(), video()) { error("不得登记") }
        }

        assertOldOutputs(backend)
    }

    private suspend fun assertOldOutputs(backend: MemoryBackend) {
        assertThat(backend.text(videoPath)).isEqualTo("旧视频")
        assertThat(backend.text(subtitlePath)).isEqualTo("旧字幕")
    }

    private suspend fun failure(block: suspend () -> Unit): Exception {
        try { block() } catch (failure: Exception) { return failure }
        throw AssertionError("应抛出事务失败")
    }

    /** fake 也遵守流式新建失败清理契约，归属不明用例显式模拟后端恢复错误。 */
    private class MemoryBackend : ExternalFolderBackend {
        private data class Node(val bytes: ByteArray, val identity: String, val directory: Boolean = false)
        private val nodes = linkedMapOf("/" to Node(byteArrayOf(), "root", true))
        private var sequence = 0
        var streamWrites = 0
        var failWritePath: String? = null
        var writeFailure: Exception = ExternalFolderException("IO", "模拟写入失败")
        var leaveUnknownOnFailure = false
        var afterRead: (suspend (String) -> Unit)? = null
        var afterStreamWrite: (suspend (String) -> Unit)? = null
        val deletions = mutableListOf<Triple<String, String?, String?>>()

        fun oldOutputs() {
            put("project/cuts/S1E01-v1.mp4", "旧视频")
            put("project/cuts/S1E01-v1.srt", "旧字幕")
        }

        fun put(path: String, text: String, keepIdentity: Boolean = false) {
            val normalized = "/${path.trimStart('/')}"
            normalized.substringBeforeLast('/').split('/').filter(String::isNotEmpty).fold("") { parent, part ->
                "$parent/$part".also { nodes.putIfAbsent(it, Node(byteArrayOf(), "dir-${++sequence}", true)) }
            }
            nodes[normalized] = Node(text.toByteArray(), if (keepIdentity) nodes.getValue(normalized).identity else "file-${++sequence}")
        }

        fun text(path: String) = nodes.getValue("/${path.trimStart('/')}").bytes.toString(Charsets.UTF_8)
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        override suspend fun stat(path: String): ExternalFolderEntry? = nodes[path]?.let {
            ExternalFolderEntry(path, path.substringAfterLast('/'), it.directory, it.bytes.size.toLong(), 1, if (it.directory) null else hash(it.bytes), it.identity)
        }
        override suspend fun list(path: String) = nodes.keys.filter { it != "/" && it.substringBeforeLast('/').ifEmpty { "/" } == path }.map { stat(it)!! }
        override suspend fun read(path: String, maxBytes: Long): ByteArray = error("成片存储必须使用流式读取")
        override suspend fun write(path: String, content: ByteArray, expectedSha256: String?, createOnly: Boolean): ExternalFolderEntry = error("成片存储必须使用流式写入")
        override suspend fun <T> readStream(path: String, maxBytes: Long, consume: suspend (InputStream) -> T): T {
            val node = nodes[path] ?: throw ExternalFolderException("NOT_FOUND", "文件不存在")
            require(node.bytes.size <= maxBytes)
            return node.bytes.inputStream().use { consume(it) }.also { afterRead?.invoke(path) }
        }
        override suspend fun writeStream(path: String, expectedSize: Long, createOnly: Boolean, produce: suspend (OutputStream) -> Unit): ExternalFolderEntry {
            check(createOnly)
            if (nodes.containsKey(path)) throw ExternalFolderException("CONFLICT", "目标已存在")
            streamWrites++
            val output = ByteArrayOutputStream()
            produce(output)
            check(output.size().toLong() == expectedSize)
            val node = Node(output.toByteArray(), "file-${++sequence}")
            nodes[path] = node
            if (failWritePath == path) {
                failWritePath = null
                if (leaveUnknownOnFailure) throw ExternalFolderException("RECOVERY_REQUIRED", "写入结果未知")
                nodes.remove(path)
                throw writeFailure
            }
            afterStreamWrite?.invoke(path)
            return stat(path)!!
        }
        override suspend fun createDirectory(path: String): ExternalFolderEntry {
            check(!nodes.containsKey(path))
            nodes[path] = Node(byteArrayOf(), "dir-${++sequence}", true)
            return stat(path)!!
        }
        override suspend fun delete(path: String, expectedSha256: String?): Unit = error("成片存储必须绑定身份和摘要删除")
        override suspend fun deleteForTransfer(path: String, expected: ExternalFolderEntry, expectedSha256: String?) {
            val node = nodes[path] ?: throw ExternalFolderException("CONFLICT", "文件不存在")
            if (node.identity != expected.identity || node.directory != expected.isDirectory || hash(node.bytes) != expectedSha256) {
                throw ExternalFolderException("CONFLICT", "文件被外部修改")
            }
            deletions += Triple(path, expected.identity, expectedSha256)
            nodes.remove(path)
        }
    }
}
