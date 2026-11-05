package com.promenar.infinity.data.rag

import com.google.common.truth.Truth.assertThat
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class CheckpointedEmbeddingTest {
    @TempDir lateinit var root: Path

    private val identity = EmbeddingCheckpointIdentity("hash-a", "model-a", 800, 100)
    private val chunks = (0 until 35).map { "切块$it" }

    @Test
    fun `按字符预算和数量分批并允许超预算单条独立成批`() {
        val texts = listOf("中".repeat(4_100), "文".repeat(4_000), "大".repeat(8_001)) + List(17) { "小" }
        val batches = embeddingBatchRanges(texts).map { range -> texts.slice(range) }

        assertThat(batches.map { it.size }).containsExactly(1, 1, 1, 16, 1).inOrder()
        assertThat(batches[2].single().length).isEqualTo(8_001)
    }

    @Test
    fun `失败批次重试耗尽后恢复时不重复请求已成功批次`() = runTest {
        val requests = mutableListOf<String>()
        var fail = true
        val progress = mutableListOf<EmbeddingBatchProgress>()
        val runner = CheckpointedEmbeddingRunner(EmbeddingCheckpoints(root.toFile()), retryDelay = {}) { batch ->
            requests += batch.first()
            if (batch.first() == chunks[16] && fail) throw EmbeddingBatchException("HTTP 503：服务繁忙", true, 503)
            batch.map { floatArrayOf(it.removePrefix("切块").toFloat()) }
        }

        assertThrows<EmbeddingBatchException> {
            runner.embed("workspace", "file", identity, chunks) { progress += it }
        }
        assertThat(requests.count { it == chunks[0] }).isEqualTo(1)
        assertThat(requests.count { it == chunks[16] }).isEqualTo(4)
        assertThat(progress.last().processed).isEqualTo(16)
        assertThat(progress.last().lastError).contains("503")

        fail = false
        val recovered = CheckpointedEmbeddingRunner(EmbeddingCheckpoints(root.toFile()), retryDelay = {}) { batch ->
            requests += batch.first()
            batch.map { floatArrayOf(it.removePrefix("切块").toFloat()) }
        }.embed("workspace", "file", identity, chunks)
        assertThat(recovered.map { it[0] }).containsExactlyElementsIn((0 until 35).map(Int::toFloat)).inOrder()
        assertThat(requests.count { it == chunks[0] }).isEqualTo(1)
        assertThat(requests.count { it == chunks[16] }).isEqualTo(5)
        assertThat(requests.count { it == chunks[32] }).isEqualTo(1)
    }

    @Test
    fun `内容或模型变化使已有检查点失效`() = runTest {
        var requests = 0
        val store = EmbeddingCheckpoints(root.toFile())
        val runner = CheckpointedEmbeddingRunner(store, retryDelay = {}) { batch ->
            requests++
            batch.map { floatArrayOf(1f) }
        }
        runner.embed("workspace", "file", identity, chunks)
        runner.embed("workspace", "file", identity, chunks)
        assertThat(requests).isEqualTo(3)
        runner.embed("workspace", "file", identity.copy(contentHash = "hash-b"), chunks)
        runner.embed("workspace", "file", identity.copy(contentHash = "hash-b", modelKey = "model-b"), chunks)
        assertThat(requests).isEqualTo(9)
    }

    @Test
    fun `不可重试错误立即失败并保留可读原因`() = runTest {
        var calls = 0
        val runner = CheckpointedEmbeddingRunner(EmbeddingCheckpoints(root.toFile()), retryDelay = {}) {
            calls++
            throw EmbeddingBatchException("HTTP 400：输入过长", false, 400)
        }
        val error = assertThrows<EmbeddingBatchException> { runner.embed("workspace", "file", identity, chunks) }
        assertThat(calls).isEqualTo(1)
        assertThat(error.message).contains("HTTP 400")
        assertThat(error.message).contains("输入过长")
    }

    @Test
    fun `批次重试等待遵守上游退避`() = runTest {
        val delays = mutableListOf<Long>()
        val progress = mutableListOf<EmbeddingBatchProgress>()
        var calls = 0
        val runner = CheckpointedEmbeddingRunner(EmbeddingCheckpoints(root.toFile()), retryDelay = { delays += it }) { batch ->
            if (calls++ == 0) throw EmbeddingBatchException("HTTP 429：限流", true, 429, 7_000)
            batch.map { floatArrayOf(1f) }
        }
        runner.embed("workspace", "file", identity, listOf("正文")) { progress += it }
        assertThat(delays).containsExactly(7_000L)
        assertThat(progress.any { it.isRetrying && it.lastError?.contains("429") == true }).isTrue()
        assertThat(progress.last().isRetrying).isFalse()
        assertThat(progress.last().lastError).contains("429")
    }

    @Test
    fun `清理文档检查点后会重新请求且不影响其他文档`() = runTest {
        var requests = 0
        val store = EmbeddingCheckpoints(root.toFile())
        val runner = CheckpointedEmbeddingRunner(store, retryDelay = {}) { batch ->
            requests++
            batch.map { floatArrayOf(1f) }
        }
        runner.embed("workspace", "one", identity, listOf("正文"))
        runner.embed("workspace", "two", identity, listOf("正文"))
        store.clear("workspace", "one")
        runner.embed("workspace", "two", identity, listOf("正文"))
        runner.embed("workspace", "one", identity, listOf("正文"))
        assertThat(requests).isEqualTo(3)
    }

    @Test
    fun `损坏批次只重算损坏部分`() = runTest {
        val store = EmbeddingCheckpoints(root.toFile())
        val calls = mutableListOf<String>()
        val runner = CheckpointedEmbeddingRunner(store, retryDelay = {}) { batch ->
            calls += batch.first()
            batch.map { floatArrayOf(1f) }
        }
        runner.embed("workspace", "file", identity, chunks)
        val batchFile = root.toFile().walkTopDown().single { it.name == "16.bin" }
        batchFile.writeText("corrupted")
        runner.embed("workspace", "file", identity, chunks)
        assertThat(calls.count { it == chunks[0] }).isEqualTo(1)
        assertThat(calls.count { it == chunks[16] }).isEqualTo(2)
        assertThat(calls.count { it == chunks[32] }).isEqualTo(1)
    }
}
