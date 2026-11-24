package com.promenar.infinity.data.proxy

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.Address
import okhttp3.Authenticator
import okhttp3.ConnectionSpec
import okhttp3.Dns
import java.net.Proxy
import java.net.URI
import javax.net.SocketFactory
import org.junit.jupiter.api.Test

class BuiltInProxyProxiesTest {
    @Test
    fun `鉴权只响应本次回环代理且不重复提交`() {
        val live = BuiltInProxyEndpoint(41001, "test-user", "mock-test-credential")
        val source = object : BuiltInProxyRouting {
            override val endpoint = MutableStateFlow<BuiltInProxyEndpoint?>(live)
        }
        assertThat(source.proxySelector.select(URI("https://example.org"))).containsExactly(live.proxy)
        val address = Address("example.org", 80, Dns.SYSTEM, SocketFactory.getDefault(), null, null, null,
            Authenticator.NONE, null, listOf(Protocol.HTTP_1_1), listOf(ConnectionSpec.CLEARTEXT), source.proxySelector)
        val route = Route(address, live.proxy, live.address)
        fun challenge(request: Request, realm: String) = Response.Builder().request(request)
            .protocol(Protocol.HTTP_1_1).code(407).message("Proxy Authentication Required")
            .header("Proxy-Authenticate", "Basic realm=\"$realm\"").build()
        val request = Request.Builder().url("http://example.org").build()
        assertThat(source.proxyAuthenticator.authenticate(route, challenge(request, "wrong"))).isNull()
        val authenticated = source.proxyAuthenticator.authenticate(route, challenge(request, live.realm))!!
        assertThat(authenticated.header("Proxy-Authorization")).startsWith("Basic ")
        assertThat(source.proxyAuthenticator.authenticate(route, challenge(authenticated, live.realm))).isNull()
        assertThat(source.proxyAuthenticator.authenticate(Route(address, Proxy.NO_PROXY, live.address), challenge(request, live.realm))).isNull()
        source.endpoint.value = null
        assertThat(source.proxyAuthenticator.authenticate(route, challenge(request, live.realm))).isNull()
    }
}
