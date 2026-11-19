package com.promenar.infinity.data.generation

import com.promenar.infinity.domain.generation.MAX_CONCURRENT_GENERATIONS
import com.promenar.infinity.utils.InfinityLogger
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull

internal const val GENERATION_CLEANUP_TIMEOUT_MS = 2_000L
private const val CLEANUP_THREADS = 8
private const val MAX_PENDING_CLEANUPS = 2 * MAX_CONCURRENT_GENERATIONS + CLEANUP_THREADS
private val nextCleanupThread = AtomicInteger()

internal val generationCleanupDispatcher = Executors.newFixedThreadPool(CLEANUP_THREADS) {
    Thread(it, "infinity-generation-cleanup-${nextCleanupThread.incrementAndGet()}").apply { isDaemon = true }
}.asCoroutineDispatcher()

private val cleanupSlots = Semaphore(MAX_PENDING_CLEANUPS)

internal class GenerationCleanupTimeout(stage: String) : IllegalStateException("生成善后等待超时：$stage")

/** 调用方只等待结果。超时后取消工作；未退出的工作继续占用名额，避免积压新的阻塞任务。 */
internal suspend fun <T> awaitGenerationCleanup(
    stage: String,
    sessionId: String,
    timeoutMs: Long,
    dispatcher: CoroutineDispatcher,
    block: suspend () -> T,
): T {
    val started = System.nanoTime()
    fun timeout(): Nothing {
        InfinityLogger.diagnostic("generation.cleanup_timeout", mapOf(
            "sessionId" to sessionId, "stage" to stage, "elapsedMs" to (System.nanoTime() - started) / 1_000_000L,
        ))
        throw GenerationCleanupTimeout(stage)
    }
    var worker: Deferred<Result<T>>? = null
    return try {
        val result = withTimeoutOrNull(timeoutMs) {
            cleanupSlots.acquire()
            val job = CoroutineScope(dispatcher).async { runCatching { block() } }
            worker = job
            job.invokeOnCompletion { cleanupSlots.release() }
            job.await()
        } ?: timeout()
        result.getOrThrow()
    } finally {
        worker?.cancel()
    }
}
