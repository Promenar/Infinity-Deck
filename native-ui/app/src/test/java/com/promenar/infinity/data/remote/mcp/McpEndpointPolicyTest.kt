package com.promenar.infinity.data.remote.mcp

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class McpEndpointPolicyTest {
    @Test fun `HTTP与HTTPS接受公网局域网及本机地址`() {
        for (host in listOf("remote.example.test", "192.168.1.2", "127.0.0.1", "localhost", "[::1]", "100.64.0.1")) {
            for (scheme in listOf("http", "https")) {
                assertThat(McpEndpointPolicy.isAllowed("http", "$scheme://$host/mcp")).isTrue()
                assertThat(McpEndpointPolicy.isAllowed("HTTP", "$scheme://$host:8765/mcp")).isTrue()
            }
        }
    }

    @Test fun `无效端点和不支持的传输仍拒绝`() {
        for (url in listOf("file:///mcp", "http:///mcp", "http://u:p@host.test/mcp", "http://host.test:0/mcp", "http://host.test:65536/mcp", "not a url")) {
            assertThat(McpEndpointPolicy.isAllowed("http", url)).isFalse()
        }
        assertThat(McpEndpointPolicy.isAllowed("stdio", "http://host.test/mcp")).isFalse()
    }

    @Test
    fun `Bearer令牌拒绝空白控制字符与非法头部值`() {
        listOf("token-value_01", "abc.def~+/==").forEach { token ->
            assertThat(McpEndpointPolicy.isValidBearerToken(token)).isTrue()
        }
        listOf("", " ", " bearer", "Bearer token", "token\r\nX: injected", "含中文", "a=b").forEach { token ->
            assertThat(McpEndpointPolicy.isValidBearerToken(token)).isFalse()
        }
    }
}
