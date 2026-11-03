package com.promenar.infinity.data.generation

import com.promenar.infinity.data.remote.protocol.ProtocolMessage
import com.promenar.infinity.data.remote.protocol.PromptRequest

/** 面向压缩决策的近似 token 估算：ASCII 约 4 字符 1 token，其余字符按 1 token 计。 */
object ApproximateTokenEstimator {
    fun estimate(text: String): Int {
        var ascii = 0L
        var other = 0
        text.forEach { if (it.code < 0x80) ascii++ else other++ }
        return ((ascii + 3) / 4 + other).toInt()
    }

    fun estimate(message: ProtocolMessage): Int = estimateMessage(message).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun estimateMessage(message: ProtocolMessage): Long =
        MESSAGE_OVERHEAD_TOKENS.toLong() +
            estimate(message.content) +
            (if (message.role == "user" && !message.turnContext.isNullOrEmpty()) estimate(message.turnContext).toLong() + 1 else 0L) +
            estimate(message.reasoning.orEmpty()) +
            message.toolCalls.orEmpty().sumOf { estimate(it.name).toLong() + estimate(it.arguments) } +
            mediaTokens(message)

    internal fun mediaTokens(message: ProtocolMessage): Long =
        message.imageUrls.orEmpty().size.toLong() * IMAGE_TOKENS +
            message.audioData.orEmpty().size.toLong() * AUDIO_TOKENS +
            message.documentData.orEmpty().sumOf { PdfInputTokenEstimator.estimate(it).toLong() } +
            message.videoData.orEmpty().size.toLong() * VIDEO_TOKENS

    fun estimate(prompt: PromptRequest): Int = (
        prompt.messages.sumOf(::estimateMessage) +
            prompt.tools.orEmpty().sumOf {
                estimate(it.function.name + it.function.description + it.function.parameters).toLong()
            }
        ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    // 音视频按输入个数预留；PDF 仅读取有界的页树元数据。
    const val IMAGE_TOKENS = 2048
    const val AUDIO_TOKENS = 32768
    const val VIDEO_TOKENS = 65536
    const val DOCUMENT_TOKENS = PdfInputTokenEstimator.FALLBACK_TOKENS
    internal const val MESSAGE_OVERHEAD_TOKENS = 4
}

