package com.promenar.infinity.ui.renderer

import com.google.common.truth.Truth.assertThat
import java.net.InetAddress
import org.junit.jupiter.api.Test

class RichContentRequestPolicyTest {
    @Test
    fun `公网 HTTPS 与 PlantUML 服务可用`() {
        listOf("https://cdn.jsdelivr.net/npm/mermaid", "https://kroki.io/plantuml/svg/example", "https://8.8.8.8/a", "https://[2606:4700:4700::1111]/a").forEach {
            assertThat(RichContentRequestPolicy.classify(it)).isEqualTo(RichContentRequestPolicy.Kind.PUBLIC_HTTPS)
        }
    }

    @Test
    fun `仅允许指定打包资源且拒绝路径穿越和伪造本地域名`() {
        listOf("katex/katex.min.css", "katex/fonts/KaTeX_Main-Regular.woff2", "mermaid/mermaid.min.js", "echarts/echarts.min.js", "previs/three.module.min.js", "previs/previs_engine.js", "kg_template.html").forEach {
            assertThat(RichContentRequestPolicy.assetPath(RichContentRequestPolicy.BASE_URL + it)).isEqualTo(it)
        }
        listOf("../model-catalog/manifest.json", "%2e%2e/model-catalog/manifest.json", "katex/%2e%2e/model-catalog/manifest.json", "previs/../model-catalog/manifest.json", "katex/%252e%252e/secret", "katex/a%2fb", "katex/a%5cb", "missing.js").forEach {
            assertThat(RichContentRequestPolicy.classify(RichContentRequestPolicy.BASE_URL + it)).isEqualTo(RichContentRequestPolicy.Kind.BLOCKED)
        }
        listOf("https://appassets.androidplatform.net/secret", "https://appassets.androidplatform.net:8443/assets/katex/katex.min.js", "https://user@appassets.androidplatform.net/assets/katex/katex.min.js").forEach {
            assertThat(RichContentRequestPolicy.classify(it)).isEqualTo(RichContentRequestPolicy.Kind.BLOCKED)
        }
    }

    @Test
    fun `拒绝非预期协议及含凭据和不规范 URL`() {
        listOf("http://example.com/a", "file:///android_asset/katex/katex.min.js", "content://provider/a", "javascript:alert(1)", "data:text/html,hello", "blob:https://example.com/a", "ftp://example.com", "intent://example.com", "https://user:pass@example.com", "https://example.com\\@127.0.0.1", "https://example.com/\nfile", "//example.com/a", "not a url").forEach {
            assertThat(RichContentRequestPolicy.classify(it)).isEqualTo(RichContentRequestPolicy.Kind.BLOCKED)
        }
    }

    @Test
    fun `拒绝私网环回链路本地组播保留网段及常见数字变体`() {
        listOf("localhost", "a.localhost", "router.local", "intranet", "127.0.0.1", "127.1", "2130706433", "0x7f000001", "0177.0.0.1", "10.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "224.0.0.1", "255.255.255.255", "192.0.2.1", "198.18.0.1", "[::]", "[::1]", "[fc00::1]", "[fe80::1]", "[ff02::1]", "[::ffff:127.0.0.1]", "[64:ff9b::a00:1]").forEach {
            assertThat(RichContentRequestPolicy.classify("https://$it/a")).isEqualTo(RichContentRequestPolicy.Kind.BLOCKED)
        }
    }

    @Test
    fun `DNS 返回任何内网地址或空结果时整体拒绝`() {
        val publicAddress = InetAddress.getByName("8.8.8.8")
        val privateAddress = InetAddress.getByName("10.0.0.1")
        assertThat(RichContentRequestPolicy.arePublicAddresses(listOf(publicAddress))).isTrue()
        assertThat(RichContentRequestPolicy.arePublicAddresses(listOf(publicAddress, privateAddress))).isFalse()
        assertThat(RichContentRequestPolicy.arePublicAddresses(emptyList())).isFalse()
    }
}
