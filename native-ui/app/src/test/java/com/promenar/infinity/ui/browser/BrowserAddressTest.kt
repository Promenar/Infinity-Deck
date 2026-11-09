package com.promenar.infinity.ui.browser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BrowserAddressTest {
    @Test fun `空白输入不跳转`() {
        assertThat(browserAddressTarget("   ")).isNull()
    }

    @Test fun `带协议的网页地址原样打开`() {
        assertThat(browserAddressTarget(" https://example.com/a?b=1 ")).isEqualTo("https://example.com/a?b=1")
        assertThat(browserAddressTarget("HTTP://example.com")).isEqualTo("HTTP://example.com")
    }

    @Test fun `缺协议时按主机类型补全`() {
        assertThat(browserAddressTarget("example.com")).isEqualTo("https://example.com")
        assertThat(browserAddressTarget("docs.example.co.uk/path#x")).isEqualTo("https://docs.example.co.uk/path#x")
        assertThat(browserAddressTarget("192.168.1.2:8080")).isEqualTo("http://192.168.1.2:8080")
        assertThat(browserAddressTarget("localhost:3000/app")).isEqualTo("http://localhost:3000/app")
        assertThat(browserAddressTarget("node.tail123.ts.net:80")).isEqualTo("http://node.tail123.ts.net:80")
        assertThat(browserAddressTarget("nas:8888")).isEqualTo("http://nas:8888")
        assertThat(browserAddressTarget("[::1]:8888")).isEqualTo("http://[::1]:8888")
        assertThat(browserAddressTarget("fd7a:115c:a1e0::1")).isEqualTo("http://[fd7a:115c:a1e0::1]")
        assertThat(browserAddressTarget("foo.localhost")).isEqualTo("http://foo.localhost")
        assertThat(browserAddressTarget("例子.中国")).isEqualTo("https://例子.中国")
    }

    @Test fun `其它内容走 Google 搜索`() {
        assertThat(browserAddressTarget("kotlin flow")).isEqualTo("https://www.google.com/search?q=kotlin+flow")
        assertThat(browserAddressTarget("天气")).isEqualTo("https://www.google.com/search?q=%E5%A4%A9%E6%B0%94")
        assertThat(browserAddressTarget("3.14")).isEqualTo("https://www.google.com/search?q=3.14")
        assertThat(browserAddressTarget("example.com 教程")).startsWith("https://www.google.com/search?q=example.com+")
    }

    @Test fun `非网页协议不直接打开，只作为搜索词`() {
        assertThat(browserAddressTarget("javascript:alert(1)")).isEqualTo("https://www.google.com/search?q=javascript%3Aalert%281%29")
        assertThat(browserAddressTarget("file:///sdcard/a.txt")).startsWith("https://www.google.com/search?q=")
        assertThat(browserAddressTarget("intent://x#Intent;end")).startsWith("https://www.google.com/search?q=")
    }
}
