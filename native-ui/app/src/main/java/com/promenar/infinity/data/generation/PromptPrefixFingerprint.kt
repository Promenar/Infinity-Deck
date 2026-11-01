package com.promenar.infinity.data.generation

import com.promenar.infinity.data.remote.protocol.PromptRequest
import com.promenar.infinity.data.remote.protocol.ProtocolMessage
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import java.security.MessageDigest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 只保留完整哈希；历史按顺序逐条累积，任一早期变化会影响后续哈希。 */
data class PromptPrefixFingerprint(val toolsHash: String, val systemHash: String, val historyHashes: List<String>)

data class PromptPrefixComparison(
    val toolsChanged: Boolean?,
    val systemChanged: Boolean?,
    val matchingHistoryMessages: Int,
    val previousHistoryMessages: Int,
    val currentHistoryMessages: Int,
)

fun fingerprintPrompt(request: PromptRequest): PromptPrefixFingerprint {
    val system = request.messages.filter { it.role == "system" }
    var cumulative = ""
    val history = request.messages.filter { it.role != "system" }.map {
        cumulative = prefixHash(cumulative + Json.encodeToString(ProtocolMessage.serializer(), it))
        cumulative
    }
    return PromptPrefixFingerprint(
        prefixHash(Json.encodeToString(ListSerializer(ProtocolTool.serializer()), request.tools.orEmpty())),
        prefixHash(Json.encodeToString(ListSerializer(ProtocolMessage.serializer()), system)), history,
    )
}

fun comparePromptPrefixes(previous: PromptPrefixFingerprint?, current: PromptPrefixFingerprint) = PromptPrefixComparison(
    toolsChanged = previous?.let { it.toolsHash != current.toolsHash },
    systemChanged = previous?.let { it.systemHash != current.systemHash },
    matchingHistoryMessages = previous?.historyHashes?.zip(current.historyHashes)?.takeWhile { (a, b) -> a == b }?.size ?: 0,
    previousHistoryMessages = previous?.historyHashes?.size ?: 0,
    currentHistoryMessages = current.historyHashes.size,
)

/** 按会话保留上一请求，跨运行时实例有效；有界内存不保存任何提示正文。 */
internal class PromptPrefixTracker(private val maxSessions: Int = 256) {
    private val previous = object : LinkedHashMap<String, PromptPrefixFingerprint>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PromptPrefixFingerprint>?) = size > maxSessions
    }

    @Synchronized
    fun observe(sessionId: String, request: PromptRequest): String {
        val fingerprint = fingerprintPrompt(request)
        val comparison = comparePromptPrefixes(previous.put(sessionId, fingerprint), fingerprint)
        return "[PrefixCache] session=${prefixHash(sessionId).take(12)} tools=${fingerprint.toolsHash.take(12)} " +
            "toolsChanged=${comparison.toolsChanged} system=${fingerprint.systemHash.take(12)} systemChanged=${comparison.systemChanged} " +
            "historyPrefix=${comparison.matchingHistoryMessages} previousMessages=${comparison.previousHistoryMessages} " +
            "currentMessages=${comparison.currentHistoryMessages} history=${fingerprint.historyHashes.lastOrNull()?.take(12) ?: "0"}"
    }
}

private fun prefixHash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

internal val mainPromptPrefixTracker = PromptPrefixTracker()
