package com.promenar.infinity.data.mediagen.comfy

import com.promenar.infinity.domain.media.MAX_WORKSPACE_MEDIA_BYTES
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.FilterInputStream
import java.io.InputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 错误正文不回传，防止主机、代理或第三方节点把鉴权内容写入工具上下文。 */
class ComfyFailure(message: String, val retryable: Boolean = false) : IOException(message)

interface ComfyHttp {
    suspend fun get(host: ComfyHost, path: String): JsonObject
    suspend fun post(host: ComfyHost, path: String, body: JsonObject): JsonObject
    suspend fun upload(host: ComfyHost, filename: String, mimeType: String, source: InputStream, audio: Boolean = false): String
    suspend fun download(host: ComfyHost, location: String, save: suspend (InputStream, String?) -> String): String
}

class OkHttpComfyHttp(client: OkHttpClient, private val credentials: ComfyCredentials) : ComfyHttp {
    // 复用应用的 Tailnet 路由；禁止自动重试有副作用的请求与跨主机重定向。
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    override suspend fun get(host: ComfyHost, path: String): JsonObject = json(host, Request.Builder().url(endpoint(host, path)).get())
    override suspend fun post(host: ComfyHost, path: String, body: JsonObject): JsonObject = try {
        json(host, Request.Builder().url(endpoint(host, path)).post(body.toString().toRequestBody("application/json".toMediaType())))
    } catch (e: ComfyFailure) {
        if (e.retryable) throw ComfyFailure("${e.message}；提交结果未知，请检查主机队列后决定是否重试")
        throw e
    }
    override suspend fun upload(host: ComfyHost, filename: String, mimeType: String, source: InputStream, audio: Boolean): String {
        require(filename.matches(Regex("[A-Za-z0-9_.-]{1,200}"))) { "上传文件名无效" }
        val payload = object : RequestBody() {
            override fun contentType() = mimeType.toMediaType()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_WORKSPACE_MEDIA_BYTES) throw ComfyFailure("输入媒体超过工作区媒体大小上限")
                    sink.write(buffer, 0, count)
                }
            }
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(if (audio) host.audioUploadField else "image", filename, payload)
            .addFormDataPart("type", "input").addFormDataPart("overwrite", "false")
            .addFormDataPart("subfolder", "infinity").build()
        val response = json(host, Request.Builder().url(endpoint(host, if (audio) host.audioUploadPath else "/upload/image")).post(body))
        val name = (response["name"] as? JsonPrimitive)?.contentOrNull ?: throw ComfyFailure("主机上传响应缺少文件名")
        val folder = (response["subfolder"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (!safeComfyFile(name, false) || !safeComfyFile(folder, true)) throw ComfyFailure("主机上传响应的文件路径无效")
        return listOf(folder, name).filter { it.isNotEmpty() }.joinToString("/")
    }
    override suspend fun download(host: ComfyHost, location: String, save: suspend (InputStream, String?) -> String): String {
        validateComfyView(location)
        return response(host, Request.Builder().url(endpoint(host, location)).get()) { response ->
            val body = response.body ?: throw ComfyFailure("主机下载响应为空")
            if (body.contentLength() > MAX_WORKSPACE_MEDIA_BYTES) throw ComfyFailure("产物超过工作区媒体大小上限")
            val job = currentCoroutineContext().job
            LimitedComfyStream(body.byteStream(), MAX_WORKSPACE_MEDIA_BYTES) { job.ensureActive() }.use { input ->
                save(input, response.header("Content-Type")?.substringBefore(';'))
            }
        }
    }
    private suspend fun json(host: ComfyHost, builder: Request.Builder): JsonObject = response(host, builder) { response ->
        val body = response.body ?: throw ComfyFailure("主机响应为空")
        val bytes = LimitedComfyStream(body.byteStream(), 16L * 1024 * 1024) { currentThreadActive() }.use { it.readBytes() }
        try { Json.parseToJsonElement(bytes.decodeToString()).jsonObject }
        catch (_: Exception) { throw ComfyFailure("ComfyUI 返回了无法解析的 JSON") }
    }
    private suspend fun <T> response(host: ComfyHost, builder: Request.Builder, consume: suspend (Response) -> T): T = withContext(Dispatchers.IO) {
        credentials.authorization(host)?.let { builder.header("Authorization", it) }
        // 不设整次调用总时限：经 tailnet 中转的大响应只是慢；连续无数据达到主机超时才判失败。
        val call = client.newBuilder().callTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(host.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .connectTimeout(minOf(host.timeoutSeconds, 30).toLong(), TimeUnit.SECONDS)
            .readTimeout(host.timeoutSeconds.toLong(), TimeUnit.SECONDS).build().newCall(builder.build())
        try {
            coroutineScope {
                val current = AtomicReference<Response?>()
                val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call.cancel(); current.get()?.close() }
                }
                try {
                    call.awaitComfyResponse().also(current::set).use { response ->
                        if (!response.isSuccessful) throw ComfyFailure(when (response.code) {
                            401, 403 -> "ComfyUI 鉴权失败（HTTP ${response.code}），请用户检查主机凭据"
                            404 -> "ComfyUI 接口不存在（HTTP 404），请检查主机地址和上传配置"
                            400, 422 -> "ComfyUI 拒绝请求（HTTP ${response.code}），请检查模板槽位和节点参数"
                            in 300..399 -> "ComfyUI 返回重定向，请配置最终主机地址"
                            else -> "ComfyUI 请求失败（HTTP ${response.code}）"
                        }, response.code == 429 || response.code >= 500)
                        consume(response)
                    }
                } finally { cancellation.cancel() }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: ComfyFailure) { throw e }
        catch (_: SocketTimeoutException) { throw ComfyFailure("ComfyUI 请求超时，请检查主机连接", true) }
        catch (_: IOException) { throw ComfyFailure("无法连接 ComfyUI 主机或传输中断，请检查连接", true) }
        catch (_: Exception) { throw ComfyFailure("ComfyUI 请求处理失败，请检查主机配置") }
    }
    private fun endpoint(host: ComfyHost, path: String): HttpUrl {
        require(path.startsWith('/') && !path.startsWith("//") && !path.contains('\\')) { "ComfyUI 接口路径无效" }
        return (host.endpoint + path).toHttpUrl()
    }
}
/**
 * 只读取工作流实际用到的节点类型 schema（逐个 /object_info/<类型>，最多 4 个并发），
 * 慢速中转下不再拉取整份节点清单；主机缺少的类型返回空对象，由预检判为缺项。
 */
internal suspend fun ComfyHttp.objectInfoFor(host: ComfyHost, workflow: JsonObject): JsonObject {
    val types = workflow.values.mapNotNull { ((it as? JsonObject)?.get("class_type") as? JsonPrimitive)?.contentOrNull }
        .filter { type -> type.isNotBlank() && type.length <= 200 && type.none { it < ' ' || it == '\u007f' } }.distinct()
    val permits = kotlinx.coroutines.sync.Semaphore(4)
    val parts = coroutineScope {
        types.map { type ->
            async {
                permits.withPermit {
                    get(host, "/object_info/" + java.net.URLEncoder.encode(type, Charsets.UTF_8.name()).replace("+", "%20"))[type]?.let { type to it }
                }
            }
        }.awaitAll()
    }
    return JsonObject(parts.filterNotNull().toMap())
}
private fun currentThreadActive() { if (Thread.currentThread().isInterrupted) throw IOException("传输已中断") }
private suspend fun Call.awaitComfyResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            if (!continuation.isActive) response.close()
            else continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}
private class LimitedComfyStream(input: InputStream, private val limit: Long, private val active: () -> Unit) : FilterInputStream(input) {
    private var total = 0L
    override fun read(): Int { active(); val value = super.read(); if (value >= 0) add(1); return value }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int { active(); val count = `in`.read(buffer, offset, length); if (count > 0) add(count); return count }
    private fun add(count: Int) { total += count; if (total > limit) throw ComfyFailure("ComfyUI 响应超过大小上限") }
}
internal fun safeComfyFile(path: String, directory: Boolean): Boolean =
    (directory || path.isNotBlank()) && !path.startsWith('/') && !path.contains('\\') && path.none { it < ' ' || it == '\u007f' } &&
        path.split('/').none { it == ".." || it == "." } && (directory || !path.contains('/'))
internal fun validateComfyView(location: String) {
    require(location.startsWith("/view?") && !location.contains('#')) { "ComfyUI 产物地址必须是 /view 查询串" }
    val url = ("http://comfy.invalid$location").toHttpUrl()
    require(url.encodedPath == "/view" && url.queryParameterNames.all { it in setOf("filename", "subfolder", "type") } &&
        url.queryParameterNames.all { url.queryParameterValues(it).size == 1 } &&
        safeComfyFile(url.queryParameter("filename").orEmpty(), false) && safeComfyFile(url.queryParameter("subfolder").orEmpty(), true) &&
        url.queryParameter("type") in setOf("output", "temp", "input")) { "ComfyUI 产物查询路径无效" }
}
