package com.promenar.infinity.ui.settings

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.repository.CacheUsageAggregate
import com.promenar.infinity.domain.repository.ITokenStatsRepository
import com.promenar.infinity.domain.repository.ModelTokenStats
import com.promenar.infinity.domain.repository.plus
import com.promenar.infinity.domain.repository.TokenUsageAggregate
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TokenUsageViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `token errors use stable type instead of repository messages`() {
        val errorField = TokenStatsState::class.java.getDeclaredField("error")
        val operationField = TokenStatsState::class.java.getDeclaredField("operation")

        assertThat(errorField.type.name)
            .isEqualTo("com.promenar.infinity.ui.settings.TokenStatsErrorCode")
        assertThat(operationField.type.name)
            .isEqualTo("com.promenar.infinity.ui.settings.TokenStatsOperation")
        assertThat(TokenStatsState::class.java.declaredFields.map { it.name })
            .doesNotContain("isLoading")
    }

    @Test
    fun `loading and clearing expose distinct typed operations and reject duplicate clear`() = runTest {
        val loadGate = CompletableDeferred<Unit>()
        val clearGate = CompletableDeferred<Unit>()
        val repository = successfulRepository()
        coEvery { repository.getTotalUsage() } coAnswers {
            loadGate.await()
            TokenUsageAggregate(inputTokens = 8)
        }
        coEvery { repository.resetStats() } coAnswers { clearGate.await() }
        val viewModel = TokenUsageViewModel(mockk<Application>(relaxed = true), repository)

        assertThat(viewModel.state.value.operation).isEqualTo(TokenStatsOperation.LOADING)
        loadGate.complete(Unit)
        advanceUntilIdle()
        assertThat(viewModel.state.value.operation).isEqualTo(TokenStatsOperation.READY)

        viewModel.showClearConfirm()
        viewModel.clearStats()
        viewModel.clearStats()

        assertThat(viewModel.state.value.operation).isEqualTo(TokenStatsOperation.CLEARING)
        assertThat(viewModel.state.value.showClearConfirm).isFalse()
        clearGate.complete(Unit)
        advanceUntilIdle()
        assertThat(viewModel.state.value.operation).isEqualTo(TokenStatsOperation.READY)
        io.mockk.coVerify(exactly = 1) { repository.resetStats() }
    }

    @Test
    fun `load failure keeps last successful state and retry replaces typed error`() = runTest {
        val repository = successfulRepository()
        var failLoad = false
        coEvery { repository.getTotalUsage() } answers {
            if (failLoad) throw IllegalStateException("runtime detail")
            TokenUsageAggregate(inputTokens = 12, outputTokens = 3)
        }
        val viewModel = TokenUsageViewModel(mockk<Application>(relaxed = true), repository)
        advanceUntilIdle()

        failLoad = true
        viewModel.loadStats()
        advanceUntilIdle()

        assertThat(viewModel.state.value.globalInput).isEqualTo(12)
        assertThat(viewModel.state.value.error).isEqualTo(TokenStatsErrorCode.LOAD_FAILED)

        failLoad = false
        viewModel.loadStats()
        advanceUntilIdle()

        assertThat(viewModel.state.value.globalInput).isEqualTo(12)
        assertThat(viewModel.state.value.error).isNull()
    }

    @Test
    fun `clear failure preserves stats until repository succeeds`() = runTest {
        val repository = successfulRepository()
        coEvery { repository.getTotalUsage() } returns TokenUsageAggregate(inputTokens = 21, outputTokens = 4)
        coEvery { repository.resetStats() } throws IllegalStateException("runtime detail")
        val viewModel = TokenUsageViewModel(mockk<Application>(relaxed = true), repository)
        advanceUntilIdle()

        viewModel.clearStats()
        advanceUntilIdle()

        assertThat(viewModel.state.value.globalInput).isEqualTo(21)
        assertThat(viewModel.state.value.error).isEqualTo(TokenStatsErrorCode.CLEAR_FAILED)

        coEvery { repository.resetStats() } returns Unit
        viewModel.clearStats()
        advanceUntilIdle()

        assertThat(viewModel.state.value.globalInput).isEqualTo(0)
        assertThat(viewModel.state.value.error).isNull()
    }

    @Test
    fun `cache aggregate flows into state and cost uses cache prices with missing-discount flag`() = runTest {
        val repository = successfulRepository()
        val gptCache = CacheUsageAggregate(requests = 2, reportedRequests = 2, reportedInputTokens = 1_000_000, cacheReadTokens = 800_000)
        val deepSeekCache = CacheUsageAggregate(requests = 1, reportedRequests = 1, reportedInputTokens = 100_000, cacheReadTokens = 50_000)
        coEvery { repository.getTotalUsage() } returns TokenUsageAggregate(inputTokens = 1_100_000, cache = gptCache + deepSeekCache)
        coEvery { repository.getUsageByModel() } returns listOf(
            ModelTokenStats("gpt-4.1", TokenUsageAggregate(inputTokens = 1_000_000, cache = gptCache)),
            ModelTokenStats("deepseek-chat", TokenUsageAggregate(inputTokens = 100_000, cache = deepSeekCache)),
        )
        val viewModel = TokenUsageViewModel(mockk<Application>(relaxed = true), repository)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.globalCache.hitRate).isWithin(1e-9).of(850_000.0 / 1_100_000)
        val gpt = state.modelBreakdown.single { it.modelId == "gpt-4.1" }
        // 20 万普通输入 × $2 + 80 万缓存读取 × $0.5
        assertThat(gpt.costUSD).isWithin(1e-9).of(0.8)
        assertThat(gpt.cacheDiscountMissing).isFalse()
        assertThat(gpt.cache).isEqualTo(gptCache)
        val deepSeek = state.modelBreakdown.single { it.modelId == "deepseek-chat" }
        assertThat(deepSeek.cacheDiscountMissing).isTrue()
        assertThat(state.globalCacheDiscountMissing).isTrue()
        assertThat(state.globalCostUSD).isWithin(1e-9).of(gpt.costUSD + deepSeek.costUSD)
    }

    @Test fun `媒体未知费用独立展示且清理token后保留账本`() = runTest {
        val rows = listOf(com.promenar.infinity.data.mediagen.queue.MediaUsageSummary("provider", "model", null, null, 1, 1))
        val media = mockk<com.promenar.infinity.data.mediagen.queue.MediaUsageRepository>()
        io.mockk.every { media.observeSummary() } returns kotlinx.coroutines.flow.flowOf(rows)
        val vm = TokenUsageViewModel(mockk<Application>(relaxed = true), successfulRepository(), media)
        advanceUntilIdle()
        assertThat(vm.state.value.mediaUsage).isEqualTo(rows)
        assertThat(vm.state.value.globalCostUSD).isEqualTo(0.0)
        vm.clearStats()
        advanceUntilIdle()
        assertThat(vm.state.value.mediaUsage).isEqualTo(rows)
    }

    @Test fun `媒体费用加载失败可重试且不伪装为空账本`() = runTest {
        val media = mockk<com.promenar.infinity.data.mediagen.queue.MediaUsageRepository>()
        io.mockk.every { media.observeSummary() } returns kotlinx.coroutines.flow.flow { throw IllegalStateException("database") }
        val vm = TokenUsageViewModel(mockk<Application>(relaxed = true), successfulRepository(), media)
        advanceUntilIdle()
        assertThat(vm.state.value.mediaUsageUnavailable).isTrue()
        io.mockk.every { media.observeSummary() } returns kotlinx.coroutines.flow.flowOf(emptyList())
        vm.loadStats()
        advanceUntilIdle()
        assertThat(vm.state.value.mediaUsageUnavailable).isFalse()
    }

    @Test fun `媒体重试不触发已失败的token清理`() = runTest {
        val tokens = successfulRepository()
        coEvery { tokens.resetStats() } throws IllegalStateException("clear")
        val media = mockk<com.promenar.infinity.data.mediagen.queue.MediaUsageRepository>()
        io.mockk.every { media.observeSummary() } returns kotlinx.coroutines.flow.flow { throw IllegalStateException("database") }
        val vm = TokenUsageViewModel(mockk<Application>(relaxed = true), tokens, media)
        advanceUntilIdle()
        vm.clearStats()
        advanceUntilIdle()
        assertThat(vm.state.value.error).isEqualTo(TokenStatsErrorCode.CLEAR_FAILED)
        io.mockk.every { media.observeSummary() } returns kotlinx.coroutines.flow.flowOf(emptyList())
        vm.retryMediaUsage()
        advanceUntilIdle()
        assertThat(vm.state.value.mediaUsageUnavailable).isFalse()
        assertThat(vm.state.value.error).isEqualTo(TokenStatsErrorCode.CLEAR_FAILED)
        io.mockk.coVerify(exactly = 1) { tokens.resetStats() }
    }

    private fun successfulRepository(): ITokenStatsRepository = mockk(relaxed = true) {
        coEvery { getTotalUsage() } returns TokenUsageAggregate()
        coEvery { getUsageByModel() } returns emptyList()
        coEvery { getTopSessions(any()) } returns emptyList()
        coEvery { getDailyTrend(any()) } returns emptyList()
        coEvery { resetStats() } returns Unit
    }
}
