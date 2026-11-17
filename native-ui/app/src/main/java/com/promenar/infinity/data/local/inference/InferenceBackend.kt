package com.promenar.infinity.data.local.inference

import kotlinx.coroutines.flow.Flow

interface InferenceBackend {

    val backendType: BackendType

    val isLoaded: Boolean

    val contextSize: Int get() = 0

    suspend fun loadModel(path: String, config: LoadConfig): Result<Unit>

    fun generate(prompt: String, config: GenerateConfig): Flow<String>

    suspend fun embed(text: String): Result<FloatArray>

    fun release()
}

enum class BackendType(val displayName: String) {
    LLAMA_CPU("llama.cpp CPU"),
    LLAMA_VULKAN("llama.cpp Vulkan"),
    LLAMA_HEXAGON("llama.cpp Hexagon NPU"),
    EXECUTORCH_QNN("ExecuTorch QNN NPU")
}

data class LoadConfig(
    val contextSize: Int = 2048,
    val threadCount: Int = GpuDetector.recommendedThreadCount(),
    val useGpu: Boolean = false
)

data class GenerateConfig(
    val maxTokens: Int = 256,
    val temperature: Float = 0.0f,
    val topP: Float = 1.0f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.0f,
    val frequencyPenalty: Float = 0.0f,
    val presencePenalty: Float = 0.0f
)
