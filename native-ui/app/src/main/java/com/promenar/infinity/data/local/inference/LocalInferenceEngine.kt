package com.promenar.infinity.data.local.inference

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

enum class SlotType { MAIN, EMBEDDING, RERANK }

data class SlotState(
    val modelPath: String? = null,
    val isLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val loadProgress: Float = 0f,
    val backendType: BackendType = BackendType.LLAMA_CPU,
    val modelName: String = "",
    val modelSize: String = "",
    val quantization: String = "",
    val error: String? = null
)

class LlamaCppBackend : InferenceBackend {

    private var ctx: LlamaContext? = null

    override val backendType: BackendType
        get() = if (ctx?.gpuAccelerated == true) BackendType.LLAMA_VULKAN else BackendType.LLAMA_CPU

    override val isLoaded: Boolean get() = ctx != null

    override suspend fun loadModel(path: String, config: LoadConfig): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val loadedCtx = LlamaContext.load(
                    modelPath = path,
                    contextSize = config.contextSize,
                    nThreads = config.threadCount,
                    useGpu = config.useGpu
                ) ?: throw IllegalStateException("Failed to load model: $path")

                ctx?.release()
                ctx = loadedCtx
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override fun generate(prompt: String, config: GenerateConfig): Flow<String> {
        val context = ctx ?: throw IllegalStateException("No model loaded")
        return context.generate(prompt, config)
    }

    override suspend fun embed(text: String): Result<FloatArray> {
        val context = ctx ?: return Result.failure(IllegalStateException("No model loaded"))
        return withContext(Dispatchers.IO) {
            try {
                Result.success(context.embed(text))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override fun release() {
        ctx?.release()
        ctx = null
    }
}

class LocalInferenceEngine internal constructor(
    private val mainBackend: InferenceBackend,
    private val embeddingBackend: InferenceBackend,
    private val rerankBackend: InferenceBackend,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(appContext: Context) : this(LlamaCppBackend(), LlamaCppBackend(), LlamaCppBackend())

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val operationMutex = Mutex()
    private val lifecycleGuard = Any()
    private var isReleased = false
    private var activeOperation: Job? = null

    private val _mainSlot = MutableStateFlow(SlotState())
    val mainSlot: StateFlow<SlotState> = _mainSlot.asStateFlow()

    private val _embeddingSlot = MutableStateFlow(SlotState())
    val embeddingSlot: StateFlow<SlotState> = _embeddingSlot.asStateFlow()
    private val embeddingGeneration = java.util.concurrent.atomic.AtomicLong(0)
    val embeddingModelGeneration: Long get() = embeddingGeneration.get()

    private val _rerankSlot = MutableStateFlow(SlotState())
    val rerankSlot: StateFlow<SlotState> = _rerankSlot.asStateFlow()

    var onStateChanged: (() -> Unit)? = null

    suspend fun loadModel(
        slot: SlotType,
        modelPath: String,
        config: LoadConfig = LoadConfig()
    ): Result<Unit> = withContext(ioDispatcher) {
        withEngineLock {
            // native 模型分配与槽位接管必须成对完成，取消在资源交接后传播。
            withContext(NonCancellable) {
                val (stateField, backend) = slotResources(slot)
                if (slot == SlotType.EMBEDDING) embeddingGeneration.incrementAndGet()

                stateField.value = stateField.value.copy(
                    isLoading = true,
                    loadProgress = 0f,
                    error = null
                )

                val fileName = java.io.File(modelPath).name
                val sizeBytes = java.io.File(modelPath).length()
                stateField.value = stateField.value.copy(loadProgress = 0.3f)

                backend.loadModel(modelPath, config).also { result ->
                    result.onSuccess {
                        stateField.value = SlotState(
                            modelPath = modelPath,
                            isLoaded = true,
                            isLoading = false,
                            loadProgress = 1f,
                            backendType = backend.backendType,
                            modelName = fileName,
                            modelSize = formatFileSize(sizeBytes),
                            quantization = detectQuantization(fileName)
                        )
                        onStateChanged?.invoke()
                    }.onFailure { e ->
                        stateField.value = stateField.value.copy(
                            isLoading = false,
                            isLoaded = backend.isLoaded,
                            error = e.message
                        )
                    }
                }
            }
        }
    }

    suspend fun unloadModel(slot: SlotType): Unit = withContext(ioDispatcher) {
        withEngineLock {
            val (stateField, backend) = slotResources(slot)
            if (slot == SlotType.EMBEDDING) embeddingGeneration.incrementAndGet()
            backend.release()
            stateField.value = SlotState()
            onStateChanged?.invoke()
        }
    }

    fun generate(prompt: String, config: GenerateConfig = GenerateConfig()): Flow<String> = flow {
        withEngineLock {
            check(mainBackend.isLoaded) { "No model loaded in main slot" }
            // collect 返回前保留锁；结构化取消会等待正在执行的 JNI 调用及其收尾退出。
            emitAll(mainBackend.generate(prompt, config))
        }
    }.flowOn(ioDispatcher)

    suspend fun embed(text: String): Result<FloatArray> = withContext(ioDispatcher) {
        withEngineLock {
            if (!embeddingBackend.isLoaded)
                return@withEngineLock Result.failure(IllegalStateException("No embedding model loaded"))
            embeddingBackend.embed(text)
        }
    }

    suspend fun embedBatch(texts: List<String>): Result<List<FloatArray>> = withContext(ioDispatcher) {
        withEngineLock {
            if (!embeddingBackend.isLoaded)
                return@withEngineLock Result.failure(IllegalStateException("No embedding model loaded"))
            val results = mutableListOf<FloatArray>()
            for (text in texts) {
                embeddingBackend.embed(text).fold(
                    onSuccess = { results.add(it) },
                    onFailure = { return@withEngineLock Result.failure(it) }
                )
            }
            Result.success(results)
        }
    }

    suspend fun rerank(
        query: String,
        documents: List<String>,
        topN: Int = 5
    ): Result<List<Pair<Int, Float>>> = withContext(ioDispatcher) {
        withEngineLock {
            if (!rerankBackend.isLoaded)
                return@withEngineLock Result.failure(IllegalStateException("No reranker model loaded"))

            val queryEmb = rerankBackend.embed(query)
                .getOrElse { return@withEngineLock Result.failure(it) }

            val scored = documents.mapIndexed { idx, doc ->
                val docEmb = rerankBackend.embed(doc)
                    .getOrElse { return@withEngineLock Result.failure(it) }
                Pair(idx, cosineSimilarity(queryEmb, docEmb))
            }.sortedByDescending { it.second }.take(topN)

            Result.success(scored)
        }
    }

    private fun slotResources(slot: SlotType) = when (slot) {
        SlotType.MAIN -> _mainSlot to mainBackend
        SlotType.EMBEDDING -> _embeddingSlot to embeddingBackend
        SlotType.RERANK -> _rerankSlot to rerankBackend
    }

    private suspend fun <T> withEngineLock(block: suspend () -> T): T {
        synchronized(lifecycleGuard) { check(!isReleased) { "Local inference engine is released" } }
        return operationMutex.withLock {
            coroutineScope {
                currentCoroutineContext().ensureActive()
                val operation = currentCoroutineContext().job
                synchronized(lifecycleGuard) {
                    check(!isReleased) { "Local inference engine is released" }
                    activeOperation = operation
                }
                try {
                    block()
                } finally {
                    synchronized(lifecycleGuard) { activeOperation = null }
                }
            }
        }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        return if (normA == 0f || normB == 0f) 0f else dot / (sqrt(normA) * sqrt(normB))
    }

    fun release() {
        val operation = synchronized(lifecycleGuard) {
            if (isReleased) return
            isReleased = true
            activeOperation
        }
        operation?.cancel(CancellationException("Local inference engine is released"))
        // 不阻塞调用线程；资源释放仍需等持锁操作完全退出。
        scope.launch {
            try {
                operationMutex.withLock {
                    mainBackend.release()
                    embeddingGeneration.incrementAndGet()
                    embeddingBackend.release()
                    rerankBackend.release()
                    _mainSlot.value = SlotState()
                    _embeddingSlot.value = SlotState()
                    _rerankSlot.value = SlotState()
                    onStateChanged?.invoke()
                }
            } finally {
                scope.cancel()
            }
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
            else -> "$bytes B"
        }
    }

    private fun detectQuantization(fileName: String): String {
        val patterns = listOf(
            "Q8_0", "Q6_K", "Q5_K_M", "Q5_K_S", "Q4_K_M", "Q4_K_S",
            "Q3_K_M", "Q2_K", "F16", "F32"
        )
        return patterns.find { fileName.contains(it, ignoreCase = true) } ?: "Unknown"
    }
}
