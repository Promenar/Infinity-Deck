package com.promenar.infinity.ui.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.model.estimateTokenCost
import com.promenar.infinity.data.model.findModelPricing
import com.promenar.infinity.data.model.findModelSpec
import com.promenar.infinity.domain.repository.CacheUsageAggregate
import com.promenar.infinity.domain.repository.DailyTokenStats
import com.promenar.infinity.domain.repository.CategoryTokenStats
import com.promenar.infinity.domain.repository.ITokenStatsRepository
import com.promenar.infinity.domain.repository.SessionTokenUsage
import com.promenar.infinity.domain.repository.TokenUsageAggregate
import com.promenar.infinity.utils.InfinityLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ModelCostInfo(
    val name: String,
    val modelId: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val costUSD: Double,
    val pricingAvailable: Boolean,
    val cache: CacheUsageAggregate = CacheUsageAggregate(),
    /** 有缓存读写但缺缓存价，费用按普通输入价计。 */
    val cacheDiscountMissing: Boolean = false,
)

enum class TokenStatsErrorCode { LOAD_FAILED, CLEAR_FAILED }
enum class TokenStatsOperation { LOADING, READY, CLEARING }

data class TokenStatsState(
    val globalInput: Long = 0,
    val globalOutput: Long = 0,
    val globalCostUSD: Double = 0.0,
    val globalHasEstimated: Boolean = false,
    /** 主模型输入缓存汇总。 */
    val globalCache: CacheUsageAggregate = CacheUsageAggregate(),
    val globalCacheDiscountMissing: Boolean = false,
    val modelBreakdown: List<ModelCostInfo> = emptyList(),
    val mediaUsage: List<com.promenar.infinity.data.mediagen.queue.MediaUsageSummary> = emptyList(),
    val mediaUsageUnavailable: Boolean = false,
    val topSessions: List<SessionTokenUsage> = emptyList(),
    val dailyTrend: List<DailyTokenStats> = emptyList(),
    val categoryBreakdown: List<CategoryTokenStats> = emptyList(),
    val operation: TokenStatsOperation = TokenStatsOperation.READY,
    val error: TokenStatsErrorCode? = null,
    val showClearConfirm: Boolean = false
)

class TokenUsageViewModel(
    application: Application,
    private val tokenStatsRepository: ITokenStatsRepository,
    private val mediaUsageRepository: com.promenar.infinity.data.mediagen.queue.MediaUsageRepository? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(TokenStatsState())
    val state: StateFlow<TokenStatsState> = _state.asStateFlow()

    private var mediaUsageObserver: Job? = null

    init { loadStats() }

    private fun observeMediaUsage() {
        val repository = mediaUsageRepository ?: return
        if (mediaUsageObserver?.isActive == true) return
        mediaUsageObserver = viewModelScope.launch {
            try {
                repository.observeSummary().collect { rows ->
                    _state.value = _state.value.copy(mediaUsage = rows, mediaUsageUnavailable = false)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                InfinityLogger.log("$TAG.mediaUsage type=${error.javaClass.simpleName}")
                _state.value = _state.value.copy(mediaUsageUnavailable = true)
            }
        }
    }

    fun retryMediaUsage() { observeMediaUsage() }

    fun loadStats() {
        observeMediaUsage()
        if (_state.value.operation != TokenStatsOperation.READY) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                operation = TokenStatsOperation.LOADING,
                error = null,
            )
            try {
                val totalUsage = tokenStatsRepository.getTotalUsage()
                val byModel = tokenStatsRepository.getUsageByModel()
                val topSessions = tokenStatsRepository.getTopSessions(10)
                val dailyTrend = tokenStatsRepository.getDailyTrend(7)
                val categories = tokenStatsRepository.getUsageByCategory()

                val modelBreakdown = byModel.map { stats ->
                    val spec = findModelSpec(stats.modelId)
                    val pricing = findModelPricing(stats.modelId)
                    val estimate = pricing?.let { estimateTokenCost(it, stats.usage) }
                    ModelCostInfo(
                        name = spec?.note ?: stats.modelId,
                        modelId = stats.modelId,
                        inputTokens = stats.usage.inputTokens,
                        outputTokens = stats.usage.outputTokens,
                        costUSD = estimate?.total ?: 0.0,
                        pricingAvailable = pricing != null,
                        cache = stats.usage.cache,
                        cacheDiscountMissing = estimate?.cacheDiscountMissing == true,
                    )
                }

                _state.value = TokenStatsState(
                    mediaUsage = _state.value.mediaUsage,
                    mediaUsageUnavailable = _state.value.mediaUsageUnavailable,
                    globalInput = totalUsage.inputTokens,
                    globalOutput = totalUsage.outputTokens,
                    globalCostUSD = modelBreakdown.sumOf { it.costUSD },
                    globalHasEstimated = totalUsage.estimated,
                    globalCache = totalUsage.cache,
                    globalCacheDiscountMissing = modelBreakdown.any { it.cacheDiscountMissing },
                    modelBreakdown = modelBreakdown,
                    topSessions = topSessions,
                    dailyTrend = dailyTrend,
                    categoryBreakdown = categories,
                    operation = TokenStatsOperation.READY,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                InfinityLogger.logError("$TAG.loadStats", e)
                _state.value = _state.value.copy(
                    operation = TokenStatsOperation.READY,
                    error = TokenStatsErrorCode.LOAD_FAILED,
                )
            }
        }
    }

    fun showClearConfirm() {
        _state.value = _state.value.copy(showClearConfirm = true)
    }

    fun dismissClearConfirm() {
        _state.value = _state.value.copy(showClearConfirm = false)
    }

    fun clearStats() {
        if (_state.value.operation != TokenStatsOperation.READY) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                operation = TokenStatsOperation.CLEARING,
                error = null,
                showClearConfirm = false,
            )
            try {
                tokenStatsRepository.resetStats()
                _state.value = TokenStatsState(operation = TokenStatsOperation.READY, mediaUsage = _state.value.mediaUsage, mediaUsageUnavailable = _state.value.mediaUsageUnavailable)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                InfinityLogger.logError("$TAG.clearStats", e)
                _state.value = _state.value.copy(
                    operation = TokenStatsOperation.READY,
                    error = TokenStatsErrorCode.CLEAR_FAILED,
                )
            }
        }
    }

    companion object {
        private const val TAG = "TokenUsageVM"

        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val app = application as InfinityApplication
                    return TokenUsageViewModel(application, app.tokenStatsRepository, app.mediaUsageRepository) as T
                }
            }
    }
}
