package com.promenar.infinity.data.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.remote.protocol.PromptRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ContextCompactionServiceTest {
    @Test
    fun `生成进行中拒绝手动压缩且不执行操作`() = runTest {
        var calls = 0
        val service = ContextCompactionService(
            isGenerating = { true },
            manualCompaction = { _, _ -> calls++; emptyResult() },
        )
        val error = runCatching { service.compactNow("s") }.exceptionOrNull()
        assertThat(error).isInstanceOf(ContextCompactionBusyException::class.java)
        assertThat(calls).isEqualTo(0)
        assertThat(service.observe("s").value.failureReason).isEqualTo(ContextCompactionFailureReason.GENERATION_ACTIVE)
    }

    @Test
    fun `手动摘要失败原因与最终结果一同发布`() = runTest {
        val failedResult = emptyResult().copy(
            summaryFailed = true, failureReason = ContextCompactionFailureReason.BUDGET_EXHAUSTED,
        )
        val service = ContextCompactionService(
            isGenerating = { false },
            manualCompaction = { _, _ -> failedResult },
        )

        assertThat(service.compactNow("s")).isEqualTo(failedResult)
        val progress = service.observe("s").value
        assertThat(progress.isRunning).isFalse()
        assertThat(progress.failed).isTrue()
        assertThat(progress.result).isEqualTo(failedResult)
        assertThat(progress.failureReason).isEqualTo(ContextCompactionFailureReason.BUDGET_EXHAUSTED)
    }

    @Test
    fun `同会话手动调用串行并发布进度和空结果`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val service = ContextCompactionService(
            isGenerating = { false },
            manualCompaction = { _, progress ->
                calls++
                progress(ContextCompactionStage.FOLDING)
                if (calls == 1) { entered.complete(Unit); release.await() }
                emptyResult()
            },
        )
        val first = async { service.compactNow("s") }
        entered.await()
        val second = async { service.compactNow("s") }
        runCurrent()
        assertThat(calls).isEqualTo(1)
        assertThat(service.observe("s").value.isRunning).isTrue()
        assertThat(service.observe("s").value.stage).isEqualTo(ContextCompactionStage.FOLDING)
        release.complete(Unit)
        assertThat(first.await().summarizedTurns).isEqualTo(0)
        assertThat(second.await().newlyFoldedToolResults).isEqualTo(0)
        assertThat(calls).isEqualTo(2)
        assertThat(service.observe("s").value.isRunning).isFalse()
        assertThat(service.observe("s").value.result).isNotNull()
    }

    @Test
    fun `手动压缩期间新生成等待同一把锁且自动压缩也串行`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var generationStarted = false
        var automaticStarted = false
        val service = ContextCompactionService(
            isGenerating = { false },
            manualCompaction = { _, _ -> entered.complete(Unit); release.await(); emptyResult() },
        )
        val manual = async { service.compactNow("s") }
        entered.await()
        val generation = async { service.withGenerationStart("s") { generationStarted = true } }
        val automatic = async {
            service.compactAutomatically("s") { automaticStarted = true; emptyResult() }
        }
        runCurrent()
        assertThat(generationStarted).isFalse()
        assertThat(automaticStarted).isFalse()
        release.complete(Unit)
        manual.await(); generation.await(); automatic.await()
        assertThat(generationStarted).isTrue()
        assertThat(automaticStarted).isTrue()
    }

    private fun emptyResult() = ContextCompactionResult(
        prompt = PromptRequest(model = "model", messages = emptyList()),
        foldedToolResults = 0, summarizedTurns = 0, beforeTokens = 0, afterTokens = 0,
    )
}
