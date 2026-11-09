package com.promenar.infinity.data.tailnet

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebViewTailnetProxyTest {
    private class FakeOverride(override val supported: Boolean = true) : WebViewProxyOverride {
        val calls = mutableListOf<String>()
        var failNextSet = false
        override fun set(proxyRule: String) {
            if (failNextSet) {
                failNextSet = false
                throw IllegalStateException("boom")
            }
            calls += "set $proxyRule"
        }
        override fun clear() {
            calls += "clear"
        }
    }

    private object NoSource : TailnetEndpointSource {
        override fun isTailnetHost(host: String) = false
        override fun currentEndpoint(): TailnetProxyEndpoint? = null
        override fun awaitEndpoint(timeoutMillis: Long): TailnetProxyEndpoint? = null
    }

    private val first = TailnetProxyEndpoint(41001, "user-aaaaaaaaaaaaaaaa", "pass-aaaaaaaaaaaaaaaa")
    private val second = TailnetProxyEndpoint(41002, "user-bbbbbbbbbbbbbbbb", "pass-bbbbbbbbbbbbbbbb")

    @Test fun `运行时设代理，端点变化改设，停止时撤销`() {
        val override = FakeOverride()
        val proxy = WebViewTailnetProxy(NoSource, override) { _, _ -> }
        proxy.apply(null)
        assertThat(override.calls).isEmpty()

        proxy.apply(first)
        proxy.apply(second)
        proxy.apply(null)
        proxy.apply(null)

        assertThat(override.calls).containsExactly(
            "set http://127.0.0.1:41001",
            "set http://127.0.0.1:41002",
            "clear",
        ).inOrder()
        assertThat(proxy.active).isFalse()
    }

    @Test fun `设置失败时不视为已接入代理`() {
        val override = FakeOverride().apply { failNextSet = true }
        val proxy = WebViewTailnetProxy(NoSource, override) { _, _ -> }
        proxy.apply(first)
        assertThat(proxy.active).isFalse()
        assertThat(proxy.credentialsFor("127.0.0.1", WebViewTailnetProxy.proxyRealm(first))).isNull()
    }

    @Test fun `只对本次代理的质询应答凭据`() {
        val proxy = WebViewTailnetProxy(NoSource, FakeOverride()) { _, _ -> }
        val realm = WebViewTailnetProxy.proxyRealm(first)
        assertThat(proxy.credentialsFor("127.0.0.1", realm)).isNull()

        proxy.apply(first)
        assertThat(proxy.credentialsFor("127.0.0.1", realm)).isEqualTo(first.user to first.password)
        assertThat(proxy.credentialsFor("127.0.0.1:41001", realm)).isEqualTo(first.user to first.password)
        // 本机网站的质询（realm 不同、端口不同或主机不同）不应答。
        assertThat(proxy.credentialsFor("127.0.0.1", "infinity-tailnet")).isNull()
        assertThat(proxy.credentialsFor("127.0.0.1:8888", realm)).isNull()
        assertThat(proxy.credentialsFor("localhost", realm)).isNull()
        assertThat(proxy.credentialsFor("nas.tail3f9c.ts.net", realm)).isNull()
        assertThat(proxy.credentialsFor(null, null)).isNull()

        proxy.apply(second)
        assertThat(proxy.credentialsFor("127.0.0.1", realm)).isNull()
        proxy.apply(null)
        assertThat(proxy.credentialsFor("127.0.0.1", WebViewTailnetProxy.proxyRealm(second))).isNull()
    }
}
