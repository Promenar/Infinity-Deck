package com.promenar.infinity.data.remote

import com.promenar.infinity.data.tailnet.DisabledTailnetRouting
import com.promenar.infinity.data.tailnet.TailnetConnectGate
import com.promenar.infinity.data.tailnet.TailnetConnectResult
import com.promenar.infinity.data.tailnet.TailnetRouting
import com.promenar.infinity.data.tailnet.TailnetUnavailableException
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * 模型调用（对话协议、连接测试、向量化、重排、生图）共用的 OkHttp 引擎。
 * tailnet 目标经内置 Tailscale 本机代理，其它目标按系统默认路径；这些请求携带 API Key，
 * 内置 Tailscale 未接入时 tailnet 请求直接失败（[TailnetUnavailableException]），不回退直连。
 * 各调用方用 [client] 建自己的 HttpClient，保留各自的超时与重定向设置，共享连接池。
 * Application 启动时调用 [install]；未安装时（单元测试）路由保持未启用状态。
 */
object ModelHttp {
    @Volatile private var routingSource: () -> TailnetRouting = { DisabledTailnetRouting }
    @Volatile private var gateSource: () -> TailnetConnectGate? = { null }

    /** 传入取值函数而非实例：内置 Tailscale 在首个请求的线程上才创建，不占用主线程。 */
    fun install(routing: () -> TailnetRouting, gate: () -> TailnetConnectGate?) {
        routingSource = routing
        gateSource = gate
    }

    private val routing: TailnetRouting get() = routingSource()

    /** 首次使用时创建；路由与关口每次请求时读取，安装早晚不影响已创建的引擎。 */
    val engine: HttpClientEngine by lazy {
        OkHttp.create {
            addInterceptor(TailnetRouteGuard({ routing }, { gateSource() }))
            config {
                proxySelector(DelegatingProxySelector { routing.proxySelector })
                proxyAuthenticator { route, response -> routing.proxyAuthenticator.authenticate(route, response) }
                addNetworkInterceptor { chain -> routing.proxyAuthInterceptor.intercept(chain) }
            }
        }
    }

    fun client(block: HttpClientConfig<*>.() -> Unit = {}): HttpClient = HttpClient(engine, block)
}

/** tailnet 目标先经 [TailnetConnectGate] 确认已接入（运行在 OkHttp 调用线程，可阻塞等待）。 */
internal class TailnetRouteGuard(
    private val routing: () -> TailnetRouting,
    private val gate: () -> TailnetConnectGate?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host
        val current = gate()
        // 内置 Tailscale 关闭时 routing 不认 tailnet 目标，仍要经关口判断是否应立即失败。
        if (current != null && (routing().isTailnetHost(host) || current.mayReject(host))) {
            val result = runBlocking { current.await(host, timeoutMillis = TailnetConnectGate.REQUEST_TIMEOUT_MS) }
            if (result is TailnetConnectResult.Unavailable) throw TailnetUnavailableException(result.reason)
        }
        return chain.proceed(chain.request())
    }
}

private class DelegatingProxySelector(private val delegate: () -> ProxySelector) : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> = delegate().select(uri)
    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = delegate().connectFailed(uri, sa, ioe)
}
