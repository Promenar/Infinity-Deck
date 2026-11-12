package com.promenar.infinity.data.mediagen.comfy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** REST 负责终态，WebSocket 仅补充当前采样节点的真实步进进度。 */
interface ComfyProgress {
    suspend fun connect(host: ComfyHost, clientId: String)
    fun value(clientId: String, promptId: String): Float?
    fun close(clientId: String)
    object None : ComfyProgress {
        override suspend fun connect(host: ComfyHost, clientId: String) = Unit
        override fun value(clientId: String, promptId: String): Float? = null
        override fun close(clientId: String) = Unit
    }
}
class WebSocketComfyProgress(client: OkHttpClient, private val credentials: ComfyCredentials) : ComfyProgress {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .pingInterval(20, TimeUnit.SECONDS).build()
    private class Entry(val created: Long = System.currentTimeMillis()) {
        @Volatile var socket: WebSocket? = null
        @Volatile var prompt: String? = null
        @Volatile var progress: Float? = null
    }
    private val entries = ConcurrentHashMap<String, Entry>()
    override suspend fun connect(host: ComfyHost, clientId: String): Unit = withContext(Dispatchers.IO) {
        entries.entries.filter { System.currentTimeMillis() - it.value.created > 2 * 60 * 60 * 1000 }.forEach { close(it.key) }
        if (entries.containsKey(clientId) || entries.size >= 100) return@withContext
        val entry = Entry()
        if (entries.putIfAbsent(clientId, entry) != null) return@withContext
        try {
            val url = (host.endpoint + "/ws").toHttpUrl().newBuilder().addQueryParameter("clientId", clientId).build()
            val builder = Request.Builder().url(url)
            credentials.authorization(host)?.let { builder.header("Authorization", it) }
            entry.socket = client.newWebSocket(builder.build(), object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    parseComfyProgressEvent(text)?.let { update ->
                        if (update.completed) { entries.remove(clientId, entry); webSocket.cancel() } else { entry.prompt = update.promptId; entry.progress = update.fraction }
                    }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { response?.close(); entries.remove(clientId, entry) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { entries.remove(clientId, entry) }
            })
        } catch (e: CancellationException) { close(clientId); throw e }
        catch (_: Exception) { close(clientId) }
    }
    override fun value(clientId: String, promptId: String): Float? = entries[clientId]?.takeIf { it.prompt == promptId }?.progress
    override fun close(clientId: String) { entries.remove(clientId)?.socket?.cancel() }
}

internal data class ComfyProgressEvent(val promptId: String, val fraction: Float?, val completed: Boolean = false)
internal fun parseComfyProgressEvent(text: String): ComfyProgressEvent? {
    if (text.length > 65536) return null
    return runCatching {
        val event = Json.parseToJsonElement(text).jsonObject
        val data = event["data"] as? JsonObject ?: return null
        val prompt = (data["prompt_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        when ((event["type"] as? JsonPrimitive)?.contentOrNull) {
            "executing" -> ComfyProgressEvent(prompt, null, data["node"] == JsonNull)
            "execution_success", "execution_error", "execution_interrupted" -> ComfyProgressEvent(prompt, null, completed = true)
            "progress" -> {
                val max = (data["max"] as? JsonPrimitive)?.doubleOrNull ?: return null
                val value = (data["value"] as? JsonPrimitive)?.doubleOrNull ?: return null
                if (!max.isFinite() || max <= 0 || !value.isFinite()) null else ComfyProgressEvent(prompt, (value / max).toFloat().coerceIn(0f, 1f))
            }
            else -> null
        }
    }.getOrNull()
}
