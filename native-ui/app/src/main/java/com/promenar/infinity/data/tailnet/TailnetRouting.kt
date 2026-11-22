package com.promenar.infinity.data.tailnet

import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * 内置 Tailscale 的路由接入点：App 内的 HTTP 客户端、SSH 与子进程经它决定是否走本机分流代理。
 * 非 tailnet 目标保持原有路径（交还系统默认 [ProxySelector]，没有系统代理时即直连）。
 */
interface TailnetRouting {
    /** 是否属于 tailnet（100.64.0.0/10、fd7a:115c:a1e0::/48、MagicDNS 后缀与短名）。 */
    fun isTailnetHost(host: String): Boolean
    /** OkHttp/Ktor：tailnet 目标走本机 HTTP CONNECT 代理，其它 DIRECT。 */
    val proxySelector: java.net.ProxySelector
    val proxyAuthenticator: okhttp3.Authenticator
    /** 明文 HTTP 经本机 tailnet 代理首跳预认证网络拦截器；未接入时直通。 */
    val proxyAuthInterceptor: okhttp3.Interceptor
        get() = okhttp3.Interceptor { chain -> chain.proceed(chain.request()) }
    /** Agent 工具仅使用当前内置代理；未就绪返回 null，不触发启动或系统代理回退。 */
    fun agentProxy(): Proxy? = null
    /** JSch：tailnet 主机返回 SOCKS5 代理（带凭据），其它 null。 */
    fun jschProxy(host: String): com.jcraft.jsch.Proxy?
    /** 子进程环境：运行中返回代理变量（必须含 NO_PROXY=localhost,127.0.0.1,::1，另一工作包会让内置 git 访问 127.0.0.1 上的本机服务），未运行返回空。 */
    fun subprocessEnvironment(): Map<String, String>
}

/** 未启用或未打包内置 Tailscale：不改变任何路径。 */
object DisabledTailnetRouting : TailnetRouting {
    override fun isTailnetHost(host: String): Boolean = false
    override val proxySelector: ProxySelector = SystemDefaultProxySelector
    override val proxyAuthenticator: okhttp3.Authenticator = okhttp3.Authenticator.NONE
    override fun jschProxy(host: String): com.jcraft.jsch.Proxy? = null
    override fun subprocessEnvironment(): Map<String, String> = emptyMap()
}

/**
 * 进程内唯一的路由实例；Application 创建内置 Tailscale 后安装，此前与单元测试中为 [DisabledTailnetRouting]。
 * SSH 连接与内置命令环境等没有经构造参数注入的调用点从这里读取。
 */
object InstalledTailnetRouting {
    @Volatile
    var routing: TailnetRouting = DisabledTailnetRouting
}

/**
 * 每次选择时读取系统默认 [ProxySelector]（与 OkHttp 未设置 proxySelector 时的行为一致）；
 * 默认选择器为空或就是 tailnet 选择器自身时返回直连，避免递归。
 */
internal object SystemDefaultProxySelector : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> = selectFallback(uri, this)

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        val system = ProxySelector.getDefault()
        if (system != null && system !== this && system !is TailnetProxySelector) system.connectFailed(uri, sa, ioe)
    }

    fun selectFallback(uri: URI?, self: ProxySelector): List<Proxy> {
        val system = ProxySelector.getDefault()
        if (uri == null || system == null || system === self || system === this || system is TailnetProxySelector) {
            return listOf(Proxy.NO_PROXY)
        }
        return runCatching { system.select(uri) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: listOf(Proxy.NO_PROXY)
    }
}

/** 本次启动的本机代理：端口与随机凭据。toString 不含凭据。 */
class TailnetProxyEndpoint(val port: Int, val user: String, val password: String) {
    init {
        require(port in 1..65535) { "端口无效" }
    }

    override fun toString(): String = "TailnetProxyEndpoint(127.0.0.1:$port)"

    companion object {
        const val HOST = "127.0.0.1"
    }
}
