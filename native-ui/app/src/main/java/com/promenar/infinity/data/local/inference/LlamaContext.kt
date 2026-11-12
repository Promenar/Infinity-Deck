package com.promenar.infinity.data.local.inference

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

class LlamaContext private constructor(
    nativePtr: Long,
    val modelPath: String,
    val contextSize: Int,
    val gpuAccelerated: Boolean
) {
    private val handle = AtomicLong(nativePtr)
    private val nativePtr get() = handle.get().also { check(it != 0L) }

    companion object {
        init {
            System.loadLibrary("infinity_llama")
        }

        suspend fun load(
            modelPath: String,
            contextSize: Int = 2048,
            nThreads: Int = GpuDetector.recommendedThreadCount(),
            useGpu: Boolean = false
        ): LlamaContext? = withContext(Dispatchers.IO) {
            val ptr = nativeLoadModel(modelPath.encodeToByteArray(), contextSize, nThreads, useGpu)
            if (ptr == 0L) null
            else LlamaContext(ptr, modelPath, contextSize, useGpu)
        }

        private external fun nativeLoadModel(
            modelPath: ByteArray, nCtx: Int, nThreads: Int, useGpu: Boolean
        ): Long
    }

    suspend fun tokenize(text: String, addBos: Boolean = true): IntArray =
        withContext(Dispatchers.IO) {
            nativeTokenize(nativePtr, text.encodeToByteArray(), addBos)
        }

    suspend fun detokenize(tokens: IntArray): String =
        withContext(Dispatchers.IO) {
            nativeDetokenize(nativePtr, tokens).decodeToString()
        }

    suspend fun ingestPrompt(tokens: IntArray) {
        withContext(Dispatchers.IO) {
            check(nativeIngestPrompt(nativePtr, tokens)) { "模型无法读取提示词" }
        }
    }

    suspend fun sample(): Int = withContext(Dispatchers.IO) {
        nativeSample(nativePtr)
    }

    suspend fun decode(token: Int): Int = withContext(Dispatchers.IO) {
        nativeDecode(nativePtr, token)
    }

    suspend fun embed(text: String): FloatArray = withContext(Dispatchers.IO) {
        nativeEmbed(nativePtr, text.encodeToByteArray())
    }

    fun clear() {
        nativeClear(nativePtr)
    }

    fun release() {
        val pointer = handle.getAndSet(0L)
        if (pointer != 0L) nativeFree(pointer)
    }

    protected fun finalize() {
        release()
    }

    fun generate(prompt: String, config: GenerateConfig = GenerateConfig()): Flow<String> = flow {
        clear()

        val promptTokens = tokenize(prompt, addBos = true)
        if (promptTokens.isEmpty()) return@flow

        ingestPrompt(promptTokens)

        val utf8 = Utf8TokenBuffer()
        var currentToken = sample()
        val limit = minOf(config.maxTokens, contextSize - promptTokens.size)
        check(limit > 0 && currentToken >= 0) { "本地模型上下文不足或采样失败" }
        repeat(limit) { index ->
            if (nativeIsEndToken(nativePtr, currentToken)) {
                utf8.finish().takeIf(String::isNotEmpty)?.let { emit(it) }
                return@flow
            }
            val bytes = withContext(Dispatchers.IO) { nativeDetokenize(nativePtr, intArrayOf(currentToken)) }
            utf8.append(bytes).takeIf(String::isNotEmpty)?.let { emit(it) }
            if (index + 1 < limit) {
                currentToken = decode(currentToken)
                check(currentToken >= 0) { "本地模型生成中断" }
            }
        }
        utf8.finish().takeIf(String::isNotEmpty)?.let { emit(it) }
    }

    private external fun nativeTokenize(ptr: Long, text: ByteArray, addBos: Boolean): IntArray
    private external fun nativeDetokenize(ptr: Long, tokens: IntArray): ByteArray
    private external fun nativeIngestPrompt(ptr: Long, tokens: IntArray): Boolean
    private external fun nativeSample(ptr: Long): Int
    private external fun nativeDecode(ptr: Long, token: Int): Int
    private external fun nativeEmbed(ptr: Long, text: ByteArray): FloatArray
    private external fun nativeIsEndToken(ptr: Long, token: Int): Boolean
    private external fun nativeClear(ptr: Long)
    private external fun nativeFree(ptr: Long)
}
