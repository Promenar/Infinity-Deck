package com.promenar.infinity.data.remote.oauth

import android.content.Intent
import android.net.Uri
import com.promenar.infinity.utils.InfinityLogger
import com.promenar.infinity.data.proxy.BuiltInProxyRouting
import com.promenar.infinity.data.proxy.InstalledBuiltInProxyRouting
import com.promenar.infinity.data.proxy.applyOptionalBuiltInProxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

data class OpenRouterPkcePair(
    val codeVerifier: String,
    val codeChallenge: String,
    val state: String,
)

sealed interface OpenRouterCallbackResult {
    data class Authorized(val code: String) : OpenRouterCallbackResult
    data object StateMismatch : OpenRouterCallbackResult
    data object TimedOut : OpenRouterCallbackResult
    data object Cancelled : OpenRouterCallbackResult
    data class Failed(val reason: String) : OpenRouterCallbackResult
}

sealed interface OpenRouterExchangeResult {
    data class Success(val apiKey: String) : OpenRouterExchangeResult
    data class NetworkOrBlocked(val httpStatus: Int? = null) : OpenRouterExchangeResult
    data class Rejected(val httpStatus: Int? = null) : OpenRouterExchangeResult
    data object InvalidResponse : OpenRouterExchangeResult
}

/**
 * 接收 `infinitydeck://oauth/openrouter` 深链唤醒事件（供 loopback 回调页自动切回应用或未来直接回调使用）。
 */
object OpenRouterOAuthDeepLinkBus {
    const val SCHEME = "infinitydeck"
    const val HOST = "oauth"
    const val PATH_PREFIX = "/openrouter"
    const val DONE_DEEP_LINK = "infinitydeck://oauth/openrouter/done"

    private val _events = MutableSharedFlow<Uri>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<Uri> = _events.asSharedFlow()

    fun isOAuthCallbackIntent(intent: Intent?): Boolean {
        val data = intent?.data ?: return false
        return intent.action == Intent.ACTION_VIEW &&
            data.scheme.equals(SCHEME, ignoreCase = true) &&
            data.host.equals(HOST, ignoreCase = true) &&
            data.path.orEmpty().startsWith(PATH_PREFIX, ignoreCase = true)
    }

    fun dispatch(uri: Uri) {
        _events.tryEmit(uri)
    }
}

/**
 * OpenRouter 官方 OAuth 2.0 PKCE 协议工具：
 * - 零中转后端，遵循 RFC 7636（S256）与 RFC 8252（原生应用本机回环回调 `http://127.0.0.1:<port>/callback`）；
 * - 授权码换密钥请求默认经由 [applyOptionalBuiltInProxy] 路由，确保启用内置代理时顺畅通行；
 * - 严禁在任何日志中打印 `code`、`code_verifier` 或返回的 `sk-or-v1-...` 密钥。
 */
object OpenRouterOAuth {
    private const val TAG = "OpenRouterOAuth"
    const val AUTH_BASE_URL = "https://openrouter.ai/auth"
    const val DEFAULT_EXCHANGE_URL = "https://openrouter.ai/api/v1/auth/keys"

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val URL_ENCODER = Base64.getUrlEncoder().withoutPadding()

    fun generatePkcePair(random: SecureRandom = SecureRandom()): OpenRouterPkcePair {
        val verifierBytes = ByteArray(32).also(random::nextBytes)
        val stateBytes = ByteArray(24).also(random::nextBytes)
        val verifier = URL_ENCODER.encodeToString(verifierBytes)
        val challenge = sha256Base64Url(verifier)
        val state = URL_ENCODER.encodeToString(stateBytes)
        return OpenRouterPkcePair(
            codeVerifier = verifier,
            codeChallenge = challenge,
            state = state,
        )
    }

    internal fun sha256Base64Url(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(StandardCharsets.US_ASCII))
        return URL_ENCODER.encodeToString(digest)
    }

    fun buildAuthorizationUrl(
        callbackUrl: String,
        codeChallenge: String,
        state: String,
    ): String {
        val encodedCallback = URLEncoder.encode(callbackUrl, "UTF-8")
        val encodedChallenge = URLEncoder.encode(codeChallenge, "UTF-8")
        val encodedState = URLEncoder.encode(state, "UTF-8")
        return "$AUTH_BASE_URL?callback_url=$encodedCallback&code_challenge=$encodedChallenge&code_challenge_method=S256&state=$encodedState"
    }

    /** 解析回环 HTTP 请求首行（例如 `GET /callback?code=abc&state=xyz HTTP/1.1`）中的查询参数。 */
    internal fun parseCallbackQuery(requestLine: String?): Map<String, String>? {
        val parts = requestLine?.trim()?.split(' ') ?: return null
        if (parts.size < 2 || !parts[0].equals("GET", ignoreCase = true)) return null
        val target = parts[1]
        if (!target.startsWith("/callback")) return null
        val query = target.substringAfter('?', missingDelimiterValue = "")
        if (query.isEmpty()) return emptyMap()
        val params = LinkedHashMap<String, String>()
        query.split('&').forEach { pair ->
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            if (key.isNotEmpty()) {
                params[URLDecoder.decode(key, "UTF-8")] = URLDecoder.decode(value, "UTF-8")
            }
        }
        return params
    }

    internal fun constantTimeEquals(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        return MessageDigest.isEqual(
            a.toByteArray(StandardCharsets.UTF_8),
            b.toByteArray(StandardCharsets.UTF_8),
        )
    }

    suspend fun exchangeAuthorizationCode(
        code: String,
        codeVerifier: String,
        exchangeUrl: String = DEFAULT_EXCHANGE_URL,
        routing: () -> BuiltInProxyRouting = { InstalledBuiltInProxyRouting.routing },
        clientBuilder: () -> OkHttpClient.Builder = {
            OkHttpClient.Builder()
                .connectTimeout(18, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .writeTimeout(18, TimeUnit.SECONDS)
                .applyOptionalBuiltInProxy(routing = routing, publicTargetOnly = true)
        },
    ): OpenRouterExchangeResult = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("code", code)
            put("code_verifier", codeVerifier)
            put("code_challenge_method", "S256")
        }.toString()
        val request = Request.Builder()
            .url(exchangeUrl)
            .header("Accept", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            val client = clientBuilder().build()
            client.newCall(request).execute().use { response ->
                val status = response.code
                val body = response.body?.string().orEmpty()
                when {
                    status in 200..299 -> {
                        val key = runCatching {
                            Json.parseToJsonElement(body).jsonObject["key"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                        }.getOrDefault("")
                        if (key.isNotBlank()) OpenRouterExchangeResult.Success(apiKey = key)
                        else OpenRouterExchangeResult.InvalidResponse
                    }
                    status == 403 || status == 451 || status >= 500 -> {
                        InfinityLogger.diagnostic("oauth.openrouter.exchange_blocked", mapOf("httpStatus" to status))
                        OpenRouterExchangeResult.NetworkOrBlocked(httpStatus = status)
                    }
                    else -> {
                        InfinityLogger.diagnostic("oauth.openrouter.exchange_rejected", mapOf("httpStatus" to status))
                        OpenRouterExchangeResult.Rejected(httpStatus = status)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            InfinityLogger.logError(TAG, error)
            OpenRouterExchangeResult.NetworkOrBlocked(httpStatus = null)
        } catch (error: Exception) {
            InfinityLogger.logError(TAG, error)
            OpenRouterExchangeResult.NetworkOrBlocked(httpStatus = null)
        }
    }
}

/**
 * 本机回环（`127.0.0.1` 随机端口）一次性 OAuth 回调接收器：
 * - 仅监听回环地址，不向局域网暴露端口；
 * - 收到授权码并校验 `state` 后返回带深链自动跳回脚本的深色页面，随后立即关闭套接字。
 */
class OpenRouterLoopbackServer private constructor(
    private val serverSocket: ServerSocket,
) : AutoCloseable {
    val port: Int get() = serverSocket.localPort
    val callbackUrl: String get() = "http://127.0.0.1:$port/callback"

    suspend fun awaitAuthorizationCode(
        expectedState: String,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): OpenRouterCallbackResult = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        try {
            while (System.currentTimeMillis() < deadline && !serverSocket.isClosed) {
                val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(100L)
                serverSocket.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val socket = try {
                    serverSocket.accept()
                } catch (_: SocketTimeoutException) {
                    return@withContext OpenRouterCallbackResult.TimedOut
                } catch (_: IOException) {
                    return@withContext OpenRouterCallbackResult.Cancelled
                }

                socket.use { client ->
                    client.soTimeout = 5_000
                    val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
                    val requestLine = reader.readLine()
                    // 消费请求头直至空行，避免浏览器复位连接。
                    var headerCount = 0
                    while (headerCount < 64) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        headerCount++
                    }
                    val params = OpenRouterOAuth.parseCallbackQuery(requestLine)
                    if (params == null) {
                        writeHttpResponse(client, 404, "text/plain; charset=utf-8", "Not found")
                        continue
                    }
                    val code = params["code"]?.trim().orEmpty()
                    val returnedState = params["state"]?.trim()
                    // 兼容 OpenRouter 在部分场景未透传 state：若返回了 state 则必须严格常时间匹配。
                    if (returnedState != null && !OpenRouterOAuth.constantTimeEquals(expectedState, returnedState)) {
                        writeHttpResponse(client, 400, "text/html; charset=utf-8", buildResultHtml(success = false))
                        return@withContext OpenRouterCallbackResult.StateMismatch
                    }
                    if (code.isBlank()) {
                        writeHttpResponse(client, 400, "text/html; charset=utf-8", buildResultHtml(success = false))
                        return@withContext OpenRouterCallbackResult.Failed("missing_code")
                    }
                    writeHttpResponse(client, 200, "text/html; charset=utf-8", buildResultHtml(success = true))
                    return@withContext OpenRouterCallbackResult.Authorized(code)
                }
            }
            OpenRouterCallbackResult.TimedOut
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            OpenRouterCallbackResult.Cancelled
        } finally {
            close()
        }
    }

    override fun close() {
        runCatching { serverSocket.close() }
    }

    private fun writeHttpResponse(
        socket: java.net.Socket,
        status: Int,
        contentType: String,
        body: String,
    ) {
        val statusText = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            else -> "Not Found"
        }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $status $statusText\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        runCatching {
            val out = socket.getOutputStream()
            out.write(headers.toByteArray(StandardCharsets.US_ASCII))
            out.write(bytes)
            out.flush()
        }
    }

    private fun buildResultHtml(success: Boolean): String {
        val title = if (success) "Authorization Complete · Infinity Deck" else "Authorization Cancelled · Infinity Deck"
        val heading = if (success) "授权已完成 / Authorized" else "未完成授权 / Authorization Failed"
        val subtitle = if (success) {
            "正在返回 Infinity Deck 并加密保存密钥；若未自动跳转，请点击下方按钮或切回应用。"
        } else {
            "请切回 Infinity Deck 重新发起授权。"
        }
        val autoRedirect = if (success) {
            "<script>setTimeout(function(){window.location.replace('${OpenRouterOAuthDeepLinkBus.DONE_DEEP_LINK}');},120);</script>"
        } else {
            ""
        }
        return """<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>$title</title>
<style>
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#08090d;color:#eef2f7;font-family:-apple-system,BlinkMacSystemFont,sans-serif;padding:24px;box-sizing:border-box}
.card{max-width:380px;width:100%;background:#11141c;border:1px solid rgba(255,255,255,.12);border-radius:20px;padding:24px;text-align:center}
h1{margin:0 0 10px;font-size:18px;font-weight:600}
p{margin:0 0 18px;font-size:13px;line-height:1.55;color:#a5b0c2}
a{display:inline-block;padding:11px 22px;border-radius:999px;background:#9cc7ff;color:#081629;text-decoration:none;font-size:14px;font-weight:600}
</style>
$autoRedirect
</head>
<body>
<div class="card">
<h1>$heading</h1>
<p>$subtitle</p>
<a href="${OpenRouterOAuthDeepLinkBus.DONE_DEEP_LINK}">返回 Infinity Deck</a>
</div>
</body>
</html>"""
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 300_000L

        fun bind(): OpenRouterLoopbackServer {
            val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            return OpenRouterLoopbackServer(socket)
        }
    }
}

/**
 * 安全的剪贴板 API 密钥形态检测：
 * - 仅在前台显式检查，只识别常见无空白单行密钥形态，不回传/记录明文，展示前必须通过 [maskSecret] 脱敏。
 */
object ApiKeyClipboardDetector {
    private val KNOWN_PREFIXES = listOf("sk-", "ms-", "AIza", "gsk_", "xai-", "cpk-")
    private val ALLOWED_CHARS = Regex("^[A-Za-z0-9._\\-]+$")

    fun detectCandidate(rawText: String?): String? {
        val candidate = rawText?.trim() ?: return null
        if (candidate.length !in 18..256) return null
        if (!ALLOWED_CHARS.matches(candidate)) return null
        val matchedKnownPrefix = KNOWN_PREFIXES.any { candidate.startsWith(it) }
        // 兼容智谱 `<32位hex>.<16位token>` 格式（不含 `sk-` 前缀）。
        val matchedZhipuDotKey = candidate.contains('.') &&
            candidate.substringBefore('.').length in 24..64 &&
            candidate.substringAfter('.').length in 10..64 &&
            candidate.count { it == '.' } == 1
        return candidate.takeIf { matchedKnownPrefix || matchedZhipuDotKey }
    }

    fun maskSecret(secret: String): String {
        val trimmed = secret.trim()
        if (trimmed.length <= 10) return "••••••••"
        val head = trimmed.take(7)
        val tail = trimmed.takeLast(4)
        return "$head••••••••$tail"
    }
}
