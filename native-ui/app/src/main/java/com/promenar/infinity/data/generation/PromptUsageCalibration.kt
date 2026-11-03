package com.promenar.infinity.data.generation

import com.promenar.infinity.data.model.PromptMessageBaseline
import com.promenar.infinity.data.model.PromptUsageBaseline
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.json
import com.promenar.infinity.data.remote.protocol.ProtocolMessage
import com.promenar.infinity.data.remote.protocol.PromptRequest
import com.promenar.infinity.data.remote.protocol.canonicalizedForRequest
import java.security.MessageDigest
import kotlinx.serialization.encodeToString

/** 基线必须与当前请求前缀相同；已有媒体由上游输入数覆盖，只估算后续增量。 */
object PromptUsageCalibration {
    fun capture(session: Session, prompt: PromptRequest, inputTokens: Int, stableModelId: String? = null): PromptUsageBaseline? =
        if (inputTokens < 0) null else PromptUsageBaseline(
            session.id, modelKey(session, prompt, stableModelId), hash(session.summary.orEmpty()), toolsHash(prompt), inputTokens,
            prompt.messages.map(::snapshot),
        )

    fun estimate(session: Session, prompt: PromptRequest, stableModelId: String? = null): Int {
        val baseline = session.stats?.contextUsageBaseline
        return calibrated(session, prompt, baseline, stableModelId) ?: ApproximateTokenEstimator.estimate(prompt)
    }

    private fun calibrated(session: Session, prompt: PromptRequest, baseline: PromptUsageBaseline?, stableModelId: String?): Int? {
        if (baseline == null || baseline.version != 1 || baseline.inputTokens < 0 || baseline.sessionId != session.id ||
            baseline.modelKey != modelKey(session, prompt, stableModelId) || baseline.summaryHash != hash(session.summary.orEmpty()) ||
            baseline.toolsHash != toolsHash(prompt) || baseline.messages.size > prompt.messages.size) return null
        var tokens = baseline.inputTokens.toLong()
        prompt.messages.forEachIndexed { index, message ->
            val old = baseline.messages.getOrNull(index)
            if (old == null) tokens += ApproximateTokenEstimator.estimate(message) else {
                val current = snapshot(message)
                if (old.bodyHash != current.bodyHash) return null
                for ((kind, hashes) in current.mediaHashes) {
                    val sent = old.mediaHashes[kind].orEmpty()
                    if (hashes.size < sent.size || hashes.take(sent.size) != sent) return null
                    val added = hashes.size - sent.size
                    tokens += when (kind) {
                        "image" -> added.toLong() * ApproximateTokenEstimator.IMAGE_TOKENS
                        "audio" -> added.toLong() * ApproximateTokenEstimator.AUDIO_TOKENS
                        "video" -> added.toLong() * ApproximateTokenEstimator.VIDEO_TOKENS
                        else -> message.documentData.orEmpty().drop(sent.size).sumOf { PdfInputTokenEstimator.estimate(it).toLong() }
                    }
                }
            }
        }
        return tokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun snapshot(message: ProtocolMessage) = PromptMessageBaseline(
        bodyHash = hash(json.encodeToString(message.copy(imageUrls = null, audioData = null, videoData = null, documentData = null, mediaPaths = null))),
        mediaHashes = linkedMapOf(
            "image" to message.imageUrls.orEmpty().map { hash(it.url.orEmpty(), it.base64.orEmpty(), it.mimeType) },
            "audio" to message.audioData.orEmpty().map { hash(it.base64, it.mimeType) },
            "video" to message.videoData.orEmpty().map { hash(it.base64, it.mimeType) },
            "document" to message.documentData.orEmpty().map { hash(it.url.orEmpty(), it.base64.orEmpty(), it.mimeType, it.name.orEmpty()) },
        ),
    )

    private fun modelKey(session: Session, prompt: PromptRequest, stable: String?) =
        hash(stable?.takeIf(String::isNotBlank) ?: session.modelId?.takeIf(String::isNotBlank) ?: prompt.model, prompt.model)

    private fun toolsHash(prompt: PromptRequest) = hash(json.encodeToString(prompt.tools.orEmpty().canonicalizedForRequest()))

    private fun hash(vararg values: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
