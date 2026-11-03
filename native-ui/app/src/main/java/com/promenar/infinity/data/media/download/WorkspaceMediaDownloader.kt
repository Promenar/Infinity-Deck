package com.promenar.infinity.data.media.download

import com.promenar.infinity.data.media.MediaTypeSniffer
import com.promenar.infinity.data.network.AgentHttpRouting
import com.promenar.infinity.data.tailnet.InstalledTailnetRouting
import com.promenar.infinity.data.tailnet.TailnetRouting
import com.promenar.infinity.domain.network.*
import com.promenar.infinity.domain.media.*
import java.io.FilterInputStream
import java.io.InputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl

const val MAX_MEDIA_FETCH_BYTES: Long = 100L * 1024 * 1024

/** 下载与落地共用同一条有界流；响应与失败暂存由各自所有者关闭、清理。 */
class WorkspaceMediaDownloader(
    client: OkHttpClient,
    private val policy: MediaFetchUrlPolicy,
    private val tailnetConnected: () -> Boolean,
    private val networkPolicy: AgentNetworkPolicy = AgentNetworkPolicy(isTailnetHost = policy::isTailnet, tailnetRunning = tailnetConnected),
    private val tailnetRouting: TailnetRouting = InstalledTailnetRouting.routing,
) {
    private val client = client.newBuilder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun save(
        url: String,
        sessionId: String,
        store: WorkspaceMediaStore,
        origin: MediaOrigin,
        targetPath: String? = null,
        maxBytes: Long = MAX_MEDIA_FETCH_BYTES,
        imageOnly: Boolean = false,
    ): MediaRef = withContext(Dispatchers.IO) {
        require(maxBytes > 0 && maxBytes <= MAX_WORKSPACE_MEDIA_BYTES)
        var current = url.trim()
        var redirects = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val endpoint = try { networkPolicy.validate(current, AgentNetworkTool.MEDIA_FETCH) }
            catch (failure: NetworkAccessDenied) { fail(failure.code, failure.message.orEmpty()) }
            policy.validate(current)?.let { fail("URL_POLICY", it) }
            val uri = endpoint.uri
            val tailnet = endpoint.target == NetworkTargetClass.TAILNET
            val route = try { AgentHttpRouting.route(client, endpoint, tailnetRouting) }
            catch (failure: NetworkAccessDenied) { fail(failure.code, failure.message.orEmpty()) }
            val call = route.client.newCall(route.request(
                // 部分站点拒绝 OkHttp 默认 UA（403），与 web_fetch 一致使用浏览器式 UA。
                Request.Builder().url(current).header("User-Agent", BROWSER_USER_AGENT).get().build(),
            ))
            val result = coroutineScope {
                val activeResponse = AtomicReference<Response?>()
                // 取消在响应头和响应体读取阶段都主动关闭调用，避免阻塞到读取超时。
                val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call.cancel(); activeResponse.get()?.close() }
                }
                try {
                    call.awaitResponse().also(activeResponse::set).use { response ->
                        if (response.code in REDIRECTS) {
                            if (redirects >= 5) fail("REDIRECT_LIMIT", "媒体下载重定向超过 5 跳")
                            val location = response.header("Location") ?: fail("REDIRECT_LOCATION", "媒体重定向缺少 Location")
                            if (location.startsWith("///")) fail("REDIRECT_LOCATION", "媒体重定向地址的主机边界存在歧义")
                            val reference = runCatching { URI(location) }.getOrNull()
                                ?: fail("REDIRECT_LOCATION", "媒体重定向地址无效")
                            if (reference.rawUserInfo != null) fail("URL_POLICY", "媒体 URL 不允许包含用户信息")
                            val next = current.toHttpUrl().resolve(location)?.toString()
                                ?: fail("REDIRECT_LOCATION", "媒体重定向地址无效")
                            // 先检查原始引用，再按 HTTP URL 语义解析，兼容仅查询参数和空引用。
                            DownloadResult.Redirect(next)
                        } else {
                            if (!response.isSuccessful) fail("HTTP_ERROR", "媒体下载失败：HTTP ${response.code}")
                            val body = response.body ?: fail("EMPTY_BODY", "媒体下载响应没有内容")
                            val declared = response.header("Content-Length")?.toLongOrNull() ?: body.contentLength()
                            if (declared > maxBytes) fail("TOO_LARGE", "媒体大小超过下载上限（$maxBytes 字节）")
                            val job = currentCoroutineContext().job
                            val input = LimitedMediaInputStream(body.byteStream(), maxBytes) { job.ensureActive() }.buffered()
                            input.use {
                                it.mark(HEADER_BYTES)
                                val header = ByteArray(HEADER_BYTES)
                                var size = 0
                                while (size < header.size) {
                                    val read = it.read(header, size, header.size - size)
                                    if (read < 0) break
                                    size += read
                                }
                                it.reset()
                                if (size == 0) fail("EMPTY_BODY", "媒体下载响应为空")
                                val sniffed = MediaTypeSniffer.sniff(header.copyOf(size), null)
                                val hint = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
                                val headerIsMedia = hint?.substringBefore('/') in MEDIA_TYPES
                                if (sniffed.kind == MediaKind.FILE && !headerIsMedia) fail("NOT_MEDIA", "响应头与文件头均不是图片、音频或视频，已拒绝下载")
                                val mime = if (sniffed.kind != MediaKind.FILE) sniffed.mimeType else hint!!
                                if (imageOnly && !mime.startsWith("image/")) fail("NOT_IMAGE", "生图结果不是图片")
                                val ref = store.save(sessionId, MediaPayload.Stream { it }, origin, mime,
                                    uri.path?.substringAfterLast('/')?.takeIf(String::isNotBlank), targetPath)
                                DownloadResult.Saved(ref)
                            }
                        }
                    }
                } catch (failure: IOException) {
                    if (tailnet) com.promenar.infinity.data.network.AgentTailnetProxy.failure(failure).let { fail(it.code, it.message.orEmpty()) }
                    throw failure
                } finally { cancellation.cancel() }
            }
            when (result) {
                is DownloadResult.Saved -> return@withContext result.ref
                is DownloadResult.Redirect -> { current = result.url; redirects++ }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("媒体下载未返回")
    }

    private sealed interface DownloadResult {
        data class Redirect(val url: String) : DownloadResult
        data class Saved(val ref: MediaRef) : DownloadResult
    }

    private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(e))
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }

    companion object {
        private const val HEADER_BYTES = 4096
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
        private val MEDIA_TYPES = setOf("image", "audio", "video")
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private fun fail(code: String, message: String): Nothing = throw WorkspaceMediaException(code, message)
    }
}

/** 即使 Content-Length 缺失、虚假或使用透明解压，也以实际读出的字节数为准。 */
private class LimitedMediaInputStream(input: InputStream, private val limit: Long, private val checkCancellation: () -> Unit) : FilterInputStream(input) {
    private var count = 0L
    override fun read(): Int {
        checkCancellation()
        val value = super.read()
        if (value >= 0) counted(1)
        return value
    }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        checkCancellation()
        val size = `in`.read(bytes, offset, minOf(length.toLong(), (limit - count + 1).coerceAtLeast(1)).toInt())
        if (size > 0) counted(size)
        return size
    }
    private fun counted(size: Int) {
        count += size
        if (count > limit) throw WorkspaceMediaException("TOO_LARGE", "媒体大小超过下载上限（$limit 字节）")
    }
}
