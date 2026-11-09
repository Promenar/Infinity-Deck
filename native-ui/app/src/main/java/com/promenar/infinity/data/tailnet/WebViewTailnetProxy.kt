package com.promenar.infinity.data.tailnet

import android.webkit.HttpAuthHandler
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.promenar.infinity.utils.InfinityLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** WebView 进程级代理覆盖的最小封装，便于在 JVM 测试里替换。 */
internal interface WebViewProxyOverride {
    val supported: Boolean
    fun set(proxyRule: String)
    fun clear()
}

internal object AndroidxWebViewProxyOverride : WebViewProxyOverride {
    override val supported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

    // 只给出代理规则，保留 Chromium 隐式绕过（localhost、回环与链路本地地址），本机 Linux 与 Termux 端口直连。
    override fun set(proxyRule: String) {
        ProxyController.getInstance().setProxyOverride(ProxyConfig.Builder().addProxyRule(proxyRule).build(), Runnable::run) {}
    }

    override fun clear() {
        ProxyController.getInstance().clearProxyOverride(Runnable::run) {}
    }
}

/**
 * 内置 Tailscale 运行时把应用内全部 WebView 的请求交给本机 HTTP 代理，由代理按目标分流到 tailnet 或直连；
 * Tailscale 停止、代理端点变化或子进程退出时撤销或改设，浏览器不会因代理失效而断网。
 * 代理要求 Basic 认证：WebView 收到 407 时由 [proceedIfProxyChallenge] 只对本次代理的质询应答凭据。
 */
class WebViewTailnetProxy internal constructor(
    private val source: TailnetEndpointSource,
    private val override: WebViewProxyOverride,
    private val warn: (String, Throwable) -> Unit = { message, error -> InfinityLogger.log("[$TAG] $message type=${error.javaClass.simpleName}") },
) {
    @Volatile private var applied: TailnetProxyEndpoint? = null

    /** WebView 是否支持进程级代理覆盖；不支持时浏览器无法访问 tailnet。 */
    val supported: Boolean get() = override.supported

    /** WebView 当前是否已经经过应用内代理。 */
    val active: Boolean get() = applied != null

    /** 订阅 Tailscale 状态：运行且端点可用时设置代理，其余状态撤销。 */
    fun start(scope: CoroutineScope, state: StateFlow<TailnetState>) {
        if (!override.supported) return
        scope.launch(Dispatchers.Main.immediate) {
            state
                .map { if (it.phase == TailnetPhase.RUNNING) source.currentEndpoint() else null }
                .distinctUntilChanged { old, new -> old?.port == new?.port && old?.user == new?.user && old?.password == new?.password }
                .collect(::apply)
        }
    }

    internal fun apply(endpoint: TailnetProxyEndpoint?) {
        if (endpoint == null) {
            if (applied != null) {
                applied = null
                runCatching { override.clear() }.onFailure { warn("撤销 WebView 代理失败", it) }
            }
            return
        }
        runCatching { override.set("http://${TailnetProxyEndpoint.HOST}:${endpoint.port}") }
            .onSuccess { applied = endpoint }
            .onFailure {
                applied = null
                warn("设置 WebView 代理失败", it)
            }
    }

    /** 质询来自本次代理时返回凭据：主机为本机回环（可带端口），realm 为本次代理专属；其它质询一律不应答。 */
    internal fun credentialsFor(host: String?, realm: String?): Pair<String, String>? {
        val endpoint = applied ?: return null
        val hostMatches = host == TailnetProxyEndpoint.HOST || host == "${TailnetProxyEndpoint.HOST}:${endpoint.port}"
        if (!hostMatches || realm != proxyRealm(endpoint)) return null
        return endpoint.user to endpoint.password
    }

    /** 在 WebViewClient.onReceivedHttpAuthRequest 中调用；返回 true 表示已应答，调用方不再走默认处理。 */
    fun proceedIfProxyChallenge(handler: HttpAuthHandler, host: String?, realm: String?): Boolean {
        val credentials = credentialsFor(host, realm) ?: return false
        handler.proceed(credentials.first, credentials.second)
        return true
    }

    companion object {
        private const val TAG = "WebViewTailnetProxy"

        /** 应用启动时装配的实例；浏览器工具与各 WebViewClient 经此读取。 */
        @Volatile var installed: WebViewTailnetProxy? = null

        /** 与 tailnet 子进程 `proxy.go` 的质询 realm 一致：固定前缀加本次代理用户名。 */
        internal fun proxyRealm(endpoint: TailnetProxyEndpoint): String = "infinity-tailnet-${endpoint.user}"

        internal fun create(source: TailnetEndpointSource): WebViewTailnetProxy = WebViewTailnetProxy(source, AndroidxWebViewProxyOverride)
    }
}
