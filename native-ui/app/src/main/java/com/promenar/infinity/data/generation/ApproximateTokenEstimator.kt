package com.promenar.infinity.data.generation

import com.promenar.infinity.data.remote.protocol.ProtocolMessage
import com.promenar.infinity.data.remote.protocol.PromptRequest

/** 面向压缩决策的近似 token 估算：ASCII 约 4 字符 1 token，其余字符按 1 token 计。 */
object ApproximateTokenEstimator {
    fun estimate(text: String): Int {
        var ascii = 0
        var other = 0
        text.forEach { if (it.code < 0x80) ascii++ else other++ }
        return (ascii + 3) / 4 + other
    }

    fun estimate(message: ProtocolMessage): Int =
        MESSAGE_OVERHEAD_TOKENS +
            estimate(message.content) +
            (if (message.role == "user" && !message.turnContext.isNullOrEmpty()) estimate(message.turnContext) + 1 else 0) +
            estimate(message.reasoning.orEmpty()) +
            message.toolCalls.orEmpty().sumOf { estimate(it.name) + estimate(it.arguments) } +
            message.imageUrls.orEmpty().size * IMAGE_TOKENS +
            message.audioData.orEmpty().size * AUDIO_TOKENS +
            message.videoData.orEmpty().size * VIDEO_TOKENS

    fun estimate(prompt: PromptRequest): Int = (
        prompt.messages.sumOf { estimate(it).toLong() } +
            prompt.tools.orEmpty().sumOf {
                estimate(it.function.name + it.function.description + it.function.parameters).toLong()
            }
        ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    // 媒体估算不扫描 base64：按每个输入的保守固定值预留预算。
    const val IMAGE_TOKENS = 2048
    const val AUDIO_TOKENS = 32768
    const val VIDEO_TOKENS = 65536
    private const val MESSAGE_OVERHEAD_TOKENS = 4
}

