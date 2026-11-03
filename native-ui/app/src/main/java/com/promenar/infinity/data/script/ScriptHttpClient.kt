package com.promenar.infinity.data.script

import com.promenar.infinity.data.network.AgentHttpRouting
import com.promenar.infinity.data.network.awaitAgentResponse
import com.promenar.infinity.data.tailnet.InstalledTailnetRouting
import com.promenar.infinity.data.tailnet.TailnetController
import com.promenar.infinity.data.tailnet.TailnetPhase
import com.promenar.infinity.data.tailnet.TailnetRouting
import com.promenar.infinity.domain.network.*
import com.promenar.infinity.domain.script.*
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl

/** 每跳重新校验；非 tailnet 直连并固定 DNS，tailnet 固定内置代理与代理认证。 */
class ScriptHttpClient(
    private val tailnetRouting: TailnetRouting = InstalledTailnetRouting.routing,
    private val tailnetRunning: () -> Boolean = { (tailnetRouting as? TailnetController)?.state?.value?.phase == TailnetPhase.RUNNING },
    networkPolicy: AgentNetworkPolicy? = null,
    private val resolver: suspend (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) },
) {
    private val policy = networkPolicy ?: AgentNetworkPolicy(tailnetRouting::isTailnetHost, tailnetRunning, resolver)

    suspend fun fetch(arguments: JsonObject, budget: ScriptNetworkBudget): JsonObject = withContext(Dispatchers.IO) {
        var url = arguments["url"]?.jsonPrimitive?.content ?: throw ScriptFailure("NETWORK_DENIED", "缺少 URL")
        val method = arguments["method"]?.jsonPrimitive?.content?.uppercase(Locale.ROOT) ?: "GET"
        if (method !in setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")) throw ScriptFailure("NETWORK_DENIED", "HTTP 方法不支持")
        val body = arguments["body"]?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw ScriptFailure("NETWORK_DENIED", "请求体必须为文本") }
        if (method in setOf("GET", "HEAD") && body != null) throw ScriptFailure("NETWORK_DENIED", "GET/HEAD 不接受请求体")
        val headers = arguments["headers"]?.jsonObject.orEmpty()
        if (headers.size > 32 || headers.toString().toByteArray().size > 8192) throw ScriptFailure("NETWORK_LIMIT", "请求头超过限制")
        val bytes = body?.toByteArray(Charsets.UTF_8)
        var origin: String? = null
        repeat(4) { hop ->
            budget.request(bytes?.size ?: 0)
            val endpoint = try { policy.validate(url, AgentNetworkTool.SCRIPT) }
            catch (failure: NetworkAccessDenied) { throw ScriptFailure("NETWORK_DENIED", "${failure.code}：${failure.message}") }
            val tailnet = endpoint.target == NetworkTargetClass.TAILNET
            val base = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS).build()
            val route = try { AgentHttpRouting.route(base, endpoint, tailnetRouting) }
            catch (failure: NetworkAccessDenied) { throw ScriptFailure("NETWORK_DENIED", "${failure.code}：${failure.message}") }
            val client = route.client
            val builder = Request.Builder().url(url)
            val currentOrigin = builder.build().url.let { "${it.scheme}://${it.host}:${it.port}" }
            headers.forEach { (name, value) ->
                if (name.lowercase(Locale.ROOT) in setOf("host", "connection", "content-length", "transfer-encoding", "cookie", "proxy-authorization", "proxy-connection", "accept-encoding")) throw ScriptFailure("NETWORK_DENIED", "请求头不允许：${name.take(40)}")
                if (origin == null || origin == currentOrigin || !name.equals("Authorization", true)) builder.header(name, value.jsonPrimitive.content)
            }
            if (origin == null) origin = currentOrigin
            val contentType = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.jsonPrimitive?.content
            val requestBody = when {
                method in setOf("GET", "HEAD") -> null
                bytes != null -> bytes.toRequestBody(contentType?.toMediaTypeOrNull())
                method in setOf("POST", "PUT", "PATCH") -> ByteArray(0).toRequestBody()
                else -> null
            }
            val call = client.newCall(route.request(builder.method(method, requestBody).build()))
            val cancellation = Job(currentCoroutineContext()[Job])
            cancellation.invokeOnCompletion { if (it is CancellationException) call.cancel() }
            try {
                val response = call.awaitAgentResponse()
                response.use {
                    if (it.code in setOf(301, 302, 303, 307, 308)) {
                        if (hop == 3 || method !in setOf("GET", "HEAD")) throw ScriptFailure("NETWORK_DENIED", "重定向次数或方法不允许")
                        val location = it.header("Location") ?: throw ScriptFailure("NETWORK_DENIED", "重定向缺少目标")
                        url = url.toHttpUrl().resolve(location)?.toString() ?: throw ScriptFailure("NETWORK_DENIED", "重定向 URL 无效")
                    } else {
                        val stream = it.body?.byteStream()
                        val buffer = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(4096)
                        if (stream != null) while (buffer.size() <= ScriptLimits.NETWORK_BODY_BYTES) {
                            currentCoroutineContext().ensureActive()
                            val count = stream.read(chunk, 0, minOf(chunk.size, ScriptLimits.NETWORK_BODY_BYTES + 1 - buffer.size()))
                            if (count < 0) break
                            buffer.write(chunk, 0, count)
                        }
                        val responseBytes = buffer.toByteArray()
                        budget.response(responseBytes.size)
                        val responseHeaders = buildJsonObject {
                            it.headers.names().filterNot { name -> name.equals("Set-Cookie", true) }.take(32).forEach { name -> put(name, it.headers.values(name).joinToString(", ").take(1024)) }
                        }
                        return@withContext buildJsonObject {
                            put("status", it.code); put("url", url); put("headers", responseHeaders)
                            put("body", responseBytes.toString(Charsets.UTF_8))
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (tailnet && failure is java.io.IOException) com.promenar.infinity.data.network.AgentTailnetProxy.failure(failure).let {
                    throw ScriptFailure("NETWORK_DENIED", "${it.code}：${it.message}")
                }
                throw failure
            }
            finally { cancellation.complete(); call.cancel(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
        throw ScriptFailure("NETWORK_DENIED", "重定向次数超过限制")
    }
}
